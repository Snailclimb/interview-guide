package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Qwen3 Realtime ASR Service
 *
 * Provides real-time speech recognition using Alibaba Cloud DashScope's qwen3-asr-flash-realtime model.
 * This service manages WebSocket connections for multiple concurrent sessions and handles
 * audio transcription with server-side Voice Activity Detection (VAD).
 *
 * Key Features:
 * - Multi-session management with thread-safe concurrent map
 * - Server-side VAD with 400ms silence duration for automatic sentence detection
 * - Callback-based result handling for real-time transcription updates
 * - Automatic resource cleanup on session termination
 *
 * Configuration:
 * - Model: qwen3-asr-flash-realtime
 * - Audio format: PCM, 16kHz sample rate
 * - Language: Chinese (zh)
 * - VAD: Enabled with server_vad type
 *
 * @see OmniRealtimeConversation
 * @see OmniRealtimeCallback
 */
@Slf4j
@Service
public class QwenAsrService {

    // Runtime configuration values (loaded from VoiceInterviewProperties; setters kept for tests)
    private String url;

    private String model;

    private String apiKey;

    private String language;

    private String format;

    private Integer sampleRate;

    private Boolean enableTurnDetection;

    private String turnDetectionType;

    private Float turnDetectionThreshold;

    private Integer turnDetectionSilenceDurationMs;

    public QwenAsrService(VoiceInterviewProperties voiceInterviewProperties) {
        VoiceInterviewProperties.AsrConfig asr = voiceInterviewProperties.getQwen().getAsr();
        this.url = asr.getUrl();
        this.model = asr.getModel();
        this.apiKey = asr.getApiKey();
        this.language = asr.getLanguage();
        this.format = asr.getFormat();
        this.sampleRate = asr.getSampleRate();
        this.enableTurnDetection = asr.isEnableTurnDetection();
        this.turnDetectionType = asr.getTurnDetectionType();
        this.turnDetectionThreshold = asr.getTurnDetectionThreshold();
        this.turnDetectionSilenceDurationMs = asr.getTurnDetectionSilenceDurationMs();
    }

    /**
     * Active ASR sessions map.
     * Key: session ID (user-provided identifier)
     * Value: AsrSession containing the OmniRealtimeConversation instance and callbacks
     */
    private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();

    /** 防止同一 interview sessionId 上并发 stop/start；并在重连时与 {@link #sessionLocks} 配合 */
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    private Object lockForSession(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    /**
     * Initialize the ASR service.
     * This method is automatically called by Spring after the service is constructed
     * and all configuration values have been loaded from VoiceInterviewProperties.
     *
     * @throws IllegalStateException if apiKey is not configured
     */
    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API key must be configured before initializing QwenAsrService");
        }
        log.info("QwenAsrService initialized with model: {}, url: {}", model, url);
    }

    /**
     * Start a new transcription session.
     *
     * This method creates a new WebSocket connection to the DashScope ASR service
     * and sets up callbacks for handling transcription results and errors.
     *
     * The session uses server-side VAD (Voice Activity Detection) to automatically
     * detect sentence boundaries. When speech is detected and transcribed, the
     * onResult callback will be invoked with the transcribed text.
     *
     * @param sessionId Unique identifier for this session
     * @param onFinal Callback when a sentence/segment is finalized ({@code completed} event)
     * @param onError Callback invoked when errors occur
     * @throws IllegalStateException if session already exists or service not initialized
     */
    public void startTranscription(String sessionId, Consumer<String> onFinal, Consumer<Throwable> onError) {
        startTranscription(sessionId, onFinal, null, onError);
    }

    /**
     * Same as {@link #startTranscription(String, Consumer, Consumer)} but forwards partial transcripts
     * ({@code conversation.item.input_audio_transcription.text}) for live subtitles.
     *
     * @param onPartial May be null if partials are not needed
     */
    public void startTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        synchronized (lockForSession(sessionId)) {
            startTranscriptionLocked(sessionId, onFinal, onPartial, onError);
        }
    }

    /**
     * 停止旧连接并重新建立（用于 ASR WebSocket 被服务端关闭后恢复识别）。
     */
    public void restartTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        synchronized (lockForSession(sessionId)) {
            log.info("[Session: {}] Restarting DashScope ASR (stop + start)", sessionId);
            stopTranscription(sessionId);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            startTranscriptionLocked(sessionId, onFinal, onPartial, onError);

            // Verify reconnection succeeded
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    Thread.sleep(100);
                    AsrSession newSession = sessions.get(sessionId);
                    if (newSession != null && newSession.getConversation() != null) {
                        log.info("[Session: {}] ASR reconnection verified successfully", sessionId);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[Session: {}] ASR reconnection verification interrupted", sessionId);
                    return;
                }
            }

            log.warn("[Session: {}] ASR reconnection may not be fully ready after 1 second", sessionId);
        }
    }

    private void startTranscriptionLocked(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        if (sessions.containsKey(sessionId)) {
            throw new IllegalStateException("Session already exists: " + sessionId);
        }

        try {
            // Build OmniRealtimeParam with connection settings
            OmniRealtimeParam param = OmniRealtimeParam.builder()
                    .model(model)
                    .url(url)
                    .apikey(apiKey)
                    .build();

            final AtomicReference<OmniRealtimeConversation> conversationRef = new AtomicReference<>();

            // Create callback handler for WebSocket events
            OmniRealtimeCallback callback = new OmniRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("[Session: {}] WebSocket connection established", sessionId);
                }

                @Override
                public void onEvent(JsonObject message) {
                    handleServerEvent(sessionId, message, onFinal, onPartial, onError);
                }

                @Override
                public void onClose(int code, String reason) {
                    OmniRealtimeConversation closed = conversationRef.get();
                    log.warn("[Session: {}] DashScope ASR WebSocket closed - code: {}, reason: {}",
                            sessionId, code, reason);
                    // 仅移除与本次连接对应的会话，避免重连后旧 onClose 误删新连接（典型「第三轮起无声」根因）
                    sessions.compute(sessionId, (id, existing) -> {
                        if (existing != null && closed != null && existing.getConversation() == closed) {
                            return null;
                        }
                        return existing;
                    });
                }
            };

            // Create OmniRealtimeConversation instance
            OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, callback);
            conversationRef.set(conversation);

            // Store session in map BEFORE connecting to ensure hasActiveSession() returns true
            sessions.put(sessionId, new AsrSession(conversation, onFinal, onPartial, onError));

            // Connect to server asynchronously (non-blocking)
            Thread connectionThread = new Thread(() -> {
                try {
                    conversation.connect();

                    // Configure session with transcription parameters
                    OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
                    transcriptionParam.setLanguage(language);
                    transcriptionParam.setInputSampleRate(sampleRate);
                    transcriptionParam.setInputAudioFormat(format);

                    OmniRealtimeConfig config = OmniRealtimeConfig.builder()
                            .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
                            .enableTurnDetection(enableTurnDetection)
                            .turnDetectionType(turnDetectionType)
                            .turnDetectionThreshold(turnDetectionThreshold)
                            .turnDetectionSilenceDurationMs(turnDetectionSilenceDurationMs)
                            .transcriptionConfig(transcriptionParam)
                            .build();

                    // Update session with configuration
                    conversation.updateSession(config);

                    log.info("[Session: {}] Transcription session started successfully", sessionId);

                } catch (Exception e) {
                    log.error("[Session: {}] Failed to establish connection", sessionId, e);
                    sessions.compute(sessionId, (id, existing) -> {
                        if (existing != null && existing.getConversation() == conversation) {
                            return null;
                        }
                        return existing;
                    });
                    onError.accept(e);
                }
            }, "ASR-Connection-" + sessionId);
            connectionThread.setDaemon(true);
            connectionThread.start();

        } catch (Exception e) {
            String errorMsg = "Failed to create transcription session: " + sessionId;
            log.error(errorMsg, e);
            sessions.remove(sessionId);
            onError.accept(new IllegalStateException(errorMsg, e));
            throw new IllegalStateException(errorMsg, e);
        }
    }

    /**
     * Send audio data to the ASR service for transcription.
     *
     * The audio data should be in PCM format at 16kHz sample rate.
     * The data is Base64-encoded before being sent to the DashScope service.
     *
     * With server-side VAD enabled, the service will automatically detect
     * speech segments and trigger transcription when silence is detected.
     *
     * @param sessionId Session identifier
     * @param audioData Raw PCM audio bytes
     * @throws IllegalStateException if session does not exist
     */
    public void sendAudio(String sessionId, byte[] audioData) {
        AsrSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("No active session found: " + sessionId);
        }

        try {
            // Convert audio data to Base64
            String audioBase64 = Base64.getEncoder().encodeToString(audioData);

            // Send to ASR service
            session.getConversation().appendAudio(audioBase64);

            log.trace("[Session: {}] Sent {} bytes of audio data", sessionId, audioData.length);

        } catch (Exception e) {
            log.error("[Session: {}] appendAudio failed (upstream may reconnect)", sessionId, e);
            // 抛出以便 WebSocket 层执行 restartTranscription；不在此重复 onError 避免用户先看到红条再恢复
            throw new IllegalStateException("ASR append failed: " + sessionId, e);
        }
    }

    /**
     * Stop transcription and close the session.
     *
     * This method notifies the ASR service to complete any pending transcription,
     * waits for the final results, and then closes the WebSocket connection.
     *
     * @param sessionId Session identifier
     */
    public void stopTranscription(String sessionId) {
        synchronized (lockForSession(sessionId)) {
            AsrSession session = sessions.remove(sessionId);
            // Clean up the session lock to prevent memory leak
            sessionLocks.remove(sessionId);
            if (session == null) {
                log.warn("[Session: {}] Attempted to stop non-existent session", sessionId);
                return;
            }

            try {
                session.getConversation().endSession();
                log.info("[Session: {}] Transcription session stopped", sessionId);
            } catch (InterruptedException e) {
                log.error("[Session: {}] Thread interrupted while ending session", sessionId, e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[Session: {}] Error while ending session (may already be closed): {}", sessionId, e.getMessage());
            }

            try {
                session.getConversation().close();
            } catch (Exception e) {
                log.debug("[Session: {}] Connection already closed: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * Check if a session with the given ID is currently active.
     *
     * @param sessionId Session identifier
     * @return true if session exists and is active, false otherwise
     */
    public boolean hasActiveSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * Destroy the service and cleanup all active sessions.
     *
     * This method is called automatically when the Spring container shuts down.
     * It stops all active sessions and releases resources.
     */
    @PreDestroy
    public void destroy() {
        log.info("Destroying QwenAsrService with {} active sessions", sessions.size());

        // Stop all active sessions
        sessions.keySet().forEach(sessionId -> {
            try {
                stopTranscription(sessionId);
            } catch (Exception e) {
                log.error("[Session: {}] Error during cleanup", sessionId, e);
            }
        });

        sessions.clear();
        log.info("QwenAsrService destroyed successfully");
    }

    /**
     * Handle server events from the DashScope ASR service.
     *
     * This method processes various event types:
     * - session.created: Session successfully created
     * - session.updated: Session configuration updated
     * - conversation.item.input_audio_transcription.completed: Final transcription result
     * - conversation.item.input_audio_transcription.text / .delta: Partial transcription (live subtitles)
     * - error: Error occurred
     *
     * @param sessionId Session identifier
     * @param message JSON event message from server
     * @param onFinal Callback for finalized segment text
     * @param onPartial Callback for streaming partial text (optional)
     * @param onError Callback for errors
     */
    private void handleServerEvent(
            String sessionId,
            JsonObject message,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        try {
            String eventType = message.get("type").getAsString();

            log.trace("[Session: {}] Received event: {}", sessionId, eventType);

            switch (eventType) {
                case "session.created":
                    log.debug("[Session: {}] Session created on server", sessionId);
                    break;

                case "session.updated":
                    log.debug("[Session: {}] Session configuration updated", sessionId);
                    break;

                case "conversation.item.input_audio_transcription.completed":
                    // Final transcription result
                    JsonObject transcriptObj = message.getAsJsonObject();
                    String transcript = transcriptObj.get("transcript").getAsString();
                    String language = transcriptObj.has("language") ?
                            transcriptObj.get("language").getAsString() : "unknown";
                    String emotion = transcriptObj.has("emotion") ?
                            transcriptObj.get("emotion").getAsString() : "neutral";

                    log.debug("[Session: {}] Transcription completed - language: {}, emotion: {}, text: {}",
                            sessionId, language, emotion, transcript);

                    onFinal.accept(transcript);
                    break;

                case "conversation.item.input_audio_transcription.text":
                case "conversation.item.input_audio_transcription.delta":
                    dispatchPartialTranscript(sessionId, message, onPartial);
                    break;

                case "error":
                    // Error event
                    JsonObject errorObj = message.getAsJsonObject("error");
                    String errorType = errorObj.has("type") ? errorObj.get("type").getAsString() : "unknown";
                    String errorCode = errorObj.has("code") ? errorObj.get("code").getAsString() : "unknown";
                    String errorMessage = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";

                    String fullErrorMessage = String.format("ASR Error [%s/%s]: %s", errorType, errorCode, errorMessage);
                    log.error("[Session: {}] {}", sessionId, fullErrorMessage);

                    onError.accept(new IllegalStateException(fullErrorMessage));
                    break;

                case "session.finished":
                    log.debug("[Session: {}] Session finished on server", sessionId);
                    break;

                case "conversation.item.input_audio_transcription.failed":
                    log.error("[Session: {}] ASR transcription failed (single utterance): {}", sessionId, message);
                    break;

                default:
                    if (eventType != null && eventType.contains("transcription")) {
                        log.debug("[Session: {}] Unhandled transcription-related event: {}", sessionId, message);
                    } else {
                        log.trace("[Session: {}] Unhandled event type: {}", sessionId, eventType);
                    }
            }

        } catch (Exception e) {
            log.error("[Session: {}] Error processing server event", sessionId, e);
            onError.accept(e);
        }
    }

    /**
     * Forward partial / streaming ASR text for real-time UI (VAD alone does not imply visible STT).
     */
    private void dispatchPartialTranscript(
            String sessionId, JsonObject message, Consumer<String> onPartial) {
        if (onPartial == null) {
            log.trace("[Session: {}] Partial transcription received (no consumer)", sessionId);
            return;
        }
        String text = extractTranscriptPayload(message);
        if (text != null && !text.isBlank()) {
            onPartial.accept(text);
        } else {
            log.trace("[Session: {}] Partial ASR event without extractable text: {}", sessionId, message);
        }
    }

    /**
     * Extract displayable text from ASR JSON events.
     * <p>
     * For {@code conversation.item.input_audio_transcription.text}, the official preview is
     * {@code text} (confirmed prefix) + {@code stash} (draft suffix); either may be empty.
     * </p>
     */
    static String extractTranscriptPayload(JsonObject message) {
        if (message.has("transcript") && !message.get("transcript").isJsonNull()) {
            JsonElement el = message.get("transcript");
            if (el.isJsonPrimitive()) {
                return el.getAsString();
            }
        }
        // Real-time partial: text + stash (see Alibaba qwen-asr-realtime server events doc)
        if (message.has("text") || message.has("stash")) {
            String prefix = "";
            String suffix = "";
            if (message.has("text") && !message.get("text").isJsonNull() && message.get("text").isJsonPrimitive()) {
                prefix = message.get("text").getAsString();
            }
            if (message.has("stash") && !message.get("stash").isJsonNull() && message.get("stash").isJsonPrimitive()) {
                suffix = message.get("stash").getAsString();
            }
            String combined = prefix + suffix;
            if (!combined.isBlank()) {
                return combined;
            }
        }
        if (message.has("delta")) {
            JsonElement d = message.get("delta");
            if (d.isJsonPrimitive()) {
                return d.getAsString();
            }
            if (d.isJsonObject()) {
                JsonObject o = d.getAsJsonObject();
                if (o.has("text") && !o.get("text").isJsonNull()) {
                    return o.get("text").getAsString();
                }
                if (o.has("transcript") && !o.get("transcript").isJsonNull()) {
                    return o.get("transcript").getAsString();
                }
            }
        }
        if (message.has("item") && message.get("item").isJsonObject()) {
            JsonObject item = message.getAsJsonObject("item");
            if (item.has("transcript") && !item.get("transcript").isJsonNull()) {
                return item.get("transcript").getAsString();
            }
        }
        return null;
    }

    /**
     * Internal class to hold session data.
     */
    private static class AsrSession {
        private final OmniRealtimeConversation conversation;
        private final Consumer<String> onFinal;
        private final Consumer<String> onPartial;
        private final Consumer<Throwable> onError;

        AsrSession(
                OmniRealtimeConversation conversation,
                Consumer<String> onFinal,
                Consumer<String> onPartial,
                Consumer<Throwable> onError) {
            this.conversation = conversation;
            this.onFinal = onFinal;
            this.onPartial = onPartial;
            this.onError = onError;
        }

        public OmniRealtimeConversation getConversation() {
            return conversation;
        }

        public Consumer<Throwable> getOnError() {
            return onError;
        }
    }

    // Setter methods for configuration (used by Spring @Value injection or tests)

    public void setUrl(String url) {
        this.url = url;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setEnableTurnDetection(Boolean enableTurnDetection) {
        this.enableTurnDetection = enableTurnDetection;
    }

    public void setTurnDetectionType(String turnDetectionType) {
        this.turnDetectionType = turnDetectionType;
    }

    public void setTurnDetectionThreshold(Float turnDetectionThreshold) {
        this.turnDetectionThreshold = turnDetectionThreshold;
    }

    public void setTurnDetectionSilenceDurationMs(Integer turnDetectionSilenceDurationMs) {
        this.turnDetectionSilenceDurationMs = turnDetectionSilenceDurationMs;
    }
}
