package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.google.gson.JsonObject;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Qwen TTS Realtime Service (WebSocket-based)
 *
 * Provides real-time text-to-speech synthesis using Alibaba Cloud DashScope's
 * qwen-tts-realtime model via WebSocket API.
 *
 * Key Features:
 * - WebSocket-based real-time TTS synthesis
 * - User-commit mode for manual control
 * - Synchronous synthesis API with 30-second timeout protection
 * - Automatic audio chunk collection via response.audio.delta events
 * - Support for Chinese language with configurable voice, speech rate, and volume
 *
 * Configuration:
 * - Model: qwen-tts-realtime
 * - Voice: Configurable (Cherry, Serena, Ethan, etc.)
 * - Audio format: PCM, 24kHz sample rate
 * - Mode: commit (user-controlled)
 *
 * @see QwenTtsRealtime
 * @see QwenTtsRealtimeCallback
 */
@Slf4j
@Service
public class QwenTtsService {

    // Runtime configuration values (loaded from VoiceInterviewProperties; setters kept for tests)
    private String model;

    private String apiKey;

    private String voice;

    private String format;

    private Integer sampleRate;

    private String mode;

    private String languageType;

    private Float speechRate;

    private Integer volume;

    public QwenTtsService(VoiceInterviewProperties voiceInterviewProperties) {
        VoiceInterviewProperties.QwenTtsConfig tts = voiceInterviewProperties.getQwen().getTts();
        this.model = tts.getModel();
        this.apiKey = tts.getApiKey();
        this.voice = tts.getVoice();
        this.format = tts.getFormat();
        this.sampleRate = tts.getSampleRate();
        this.mode = tts.getMode();
        this.languageType = tts.getLanguageType();
        this.speechRate = tts.getSpeechRate();
        this.volume = tts.getVolume();
    }

    /**
     * Initialize the TTS service.
     * This method is automatically called by Spring after the service is constructed
     * and all configuration values have been loaded from VoiceInterviewProperties.
     *
     * @throws IllegalStateException if apiKey is not configured
     */
    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API key must be configured before initializing QwenTtsService");
        }
        log.info("QwenTtsService initialized with model: {}, voice: {}, sampleRate: {}Hz",
                 model, voice, sampleRate);
    }

    /**
     * Synthesize text to speech audio.
     *
     * This method synchronously converts text to PCM audio data using the DashScope
     * WebSocket-based TTS API. It establishes a WebSocket connection, sends the text for
     * synthesis, collects audio chunks, and returns the complete audio data.
     *
     * The method uses CountDownLatch to wait for synthesis completion with a 30-second
     * timeout to prevent indefinite blocking.
     *
     * @param text Text to synthesize (null, empty, or whitespace-only text returns empty array)
     * @return PCM audio data at configured sample rate, or empty array if synthesis fails
     */
    public byte[] synthesize(String text) {
        // Handle null, empty, or whitespace-only text
        if (text == null || text.trim().isEmpty()) {
            log.debug("Empty or null text provided, returning empty audio array");
            return new byte[0];
        }

        log.debug("Starting TTS synthesis for text: {} characters", text.length());

        // Latch for synchronous waiting
        CountDownLatch synthesisLatch = new CountDownLatch(1);

        // Container for collected audio data
        ByteArrayContainer audioContainer = new ByteArrayContainer();

        // Error container
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Response ID container for tracking
        AtomicReference<String> responseIdRef = new AtomicReference<>();

        try {
            // Build QwenTtsRealtimeParam with connection settings
            QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
                    .model(model)
                    .apikey(apiKey)
                    .build();

            // Create callback handler for WebSocket events
            QwenTtsRealtimeCallback callback = new QwenTtsRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("TTS WebSocket connection established");
                }

                @Override
                public void onEvent(JsonObject message) {
                    handleServerEvent(message, audioContainer, synthesisLatch, errorRef, responseIdRef);
                }

                @Override
                public void onClose(int code, String reason) {
                    log.debug("TTS WebSocket closed - code: {}, reason: {}", code, reason);
                    synthesisLatch.countDown();
                }
            };

            // Create QwenTtsRealtime instance
            QwenTtsRealtime qwenTtsRealtime = new QwenTtsRealtime(param, callback);

            try {
                // Connect to server (blocking)
                qwenTtsRealtime.connect();

                // Configure session with TTS parameters
                QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
                        .voice(voice)
                        .responseFormat(getAudioFormat())
                        .mode(mode)  // "commit" mode
                        .languageType(languageType)
                        .speechRate(speechRate)
                        .volume(volume)
                        .build();

                // Update session with configuration
                qwenTtsRealtime.updateSession(config);

                log.info("[TTS] Session configured with voice: {}, triggering synthesis for text (length: {})",
                         voice, text.length());

                // Send text for synthesis using commit mode
                qwenTtsRealtime.appendText(text);
                qwenTtsRealtime.commit();

                log.info("[TTS] Text sent to TTS service, waiting for audio response...");

                // Wait for synthesis completion with timeout
                boolean completed = synthesisLatch.await(30, TimeUnit.SECONDS);

                if (!completed) {
                    log.error("TTS synthesis timeout after 30 seconds");
                    return new byte[0];
                }

                // Check if error occurred
                Throwable error = errorRef.get();
                if (error != null) {
                    log.error("TTS synthesis failed", error);
                    return new byte[0];
                }

                // Return collected audio data
                byte[] audioData = audioContainer.toByteArray();
                log.info("[TTS] Synthesis completed successfully - {} bytes of audio data, responseId: {}",
                         audioData.length, responseIdRef.get());

                return audioData;

            } finally {
                // Ensure connection is closed
                try {
                    qwenTtsRealtime.close();
                } catch (Exception e) {
                    log.error("Error closing TTS connection", e);
                }
            }

        } catch (InterruptedException e) {
            log.error("TTS synthesis interrupted", e);
            Thread.currentThread().interrupt();
            return new byte[0];
        } catch (Exception e) {
            log.error("Failed to synthesize text", e);
            return new byte[0];
        }
    }

    /**
     * Get audio format for Qwen TTS Realtime.
     * Currently supports 24kHz PCM format.
     *
     * @return QwenTtsRealtimeAudioFormat enum value
     */
    private QwenTtsRealtimeAudioFormat getAudioFormat() {
        // Qwen TTS Realtime uses 24kHz by default
        return QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;
    }

    /**
     * Destroy the service and cleanup resources.
     *
     * This method is called automatically when the Spring container shuts down.
     * Currently, no persistent resources need cleanup as each synthesis creates
     * its own temporary connection.
     */
    @PreDestroy
    public void destroy() {
        log.info("QwenTtsService destroyed successfully");
    }

    /**
     * Handle server events from the DashScope TTS service.
     *
     * This method processes various event types:
     * - session.created: Session successfully created
     * - session.updated: Session configuration updated
     * - response.audio.delta: Audio chunk received
     * - response.done: Response completed
     * - error: Error occurred
     *
     * @param message JSON event message from server
     * @param audioContainer Container for collecting audio chunks
     * @param synthesisLatch Latch to signal completion
     * @param errorRef Container for error tracking
     * @param responseIdRef Container for response ID tracking
     */
    private void handleServerEvent(JsonObject message, ByteArrayContainer audioContainer,
                                    CountDownLatch synthesisLatch, AtomicReference<Throwable> errorRef,
                                    AtomicReference<String> responseIdRef) {
        try {
            String eventType = message.get("type").getAsString();

            if (log.isTraceEnabled()) {
                log.trace("Received TTS event: {}, full message: {}", eventType, message);
            } else {
                log.debug("Received TTS event: {}", eventType);
            }

            switch (eventType) {
                case "session.created":
                    String sessionId = message.has("session") && message.get("session").isJsonObject()
                            ? message.get("session").getAsJsonObject().get("id").getAsString()
                            : "unknown";
                    log.debug("TTS session created: {}", sessionId);
                    break;

                case "session.updated":
                    log.debug("TTS session configuration updated");
                    break;

                case "response.audio.delta":
                    // Audio chunk received - delta is a base64 string directly
                    if (message.has("delta")) {
                        String audioBase64 = message.get("delta").getAsString();
                        if (audioBase64 != null && !audioBase64.isEmpty()) {
                            byte[] audioChunk = Base64.getDecoder().decode(audioBase64);
                            audioContainer.append(audioChunk);
                            log.trace("Received audio chunk - {} bytes", audioChunk.length);
                        }
                    }
                    break;

                case "response.done":
                    // Response completed - this is the final event in Qwen TTS API
                    String responseId = responseIdRef.get();
                    log.debug("TTS response completed - responseId: {}", responseId);
                    synthesisLatch.countDown();
                    break;

                case "error":
                    // Error event
                    if (message.has("error")) {
                        var errorElement = message.get("error");
                        String errorType = "unknown";
                        String errorCode = "unknown";
                        String errorMessage = "Unknown error";

                        if (errorElement.isJsonObject()) {
                            JsonObject errorObj = errorElement.getAsJsonObject();
                            errorType = errorObj.has("type") ? errorObj.get("type").getAsString() : "unknown";
                            errorCode = errorObj.has("code") ? errorObj.get("code").getAsString() : "unknown";
                            errorMessage = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";
                        } else {
                            errorMessage = errorElement.toString();
                        }

                        String fullErrorMessage = String.format("TTS Error [%s/%s]: %s", errorType, errorCode, errorMessage);
                        log.error("{}", fullErrorMessage);

                        errorRef.set(new IllegalStateException(fullErrorMessage));
                        synthesisLatch.countDown();
                    }
                    break;

                default:
                    log.trace("Unhandled TTS event type: {}", eventType);
            }

        } catch (Exception e) {
            log.error("Error processing TTS server event", e);
            errorRef.set(e);
            synthesisLatch.countDown();
        }
    }

    /**
     * Internal class for efficiently collecting audio chunks.
     * Uses ByteArrayOutputStream for amortized O(1) append performance
     * instead of O(n²) copying with manual array growth.
     */
    private static class ByteArrayContainer {
        private final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        public synchronized void append(byte[] chunk) {
            baos.write(chunk, 0, chunk.length);
        }

        public synchronized byte[] toByteArray() {
            return baos.toByteArray();
        }
    }

    // Setter methods for configuration (used by Spring @Value injection or tests)

    public void setModel(String model) {
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setLanguageType(String languageType) {
        this.languageType = languageType;
    }

    public void setSpeechRate(Float speechRate) {
        this.speechRate = speechRate;
    }

    public void setVolume(Integer volume) {
        this.volume = volume;
    }
}
