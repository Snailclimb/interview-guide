package interview.guide.modules.voiceinterview.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QwenAsrService Unit Tests")
class QwenAsrServiceTest {

    private QwenAsrService asrService;

    @BeforeEach
    void setUp() {
        asrService = new QwenAsrService();

        // Set field values using reflection
        ReflectionTestUtils.setField(asrService, "url", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime");
        ReflectionTestUtils.setField(asrService, "model", "qwen3-asr-flash-realtime");
        ReflectionTestUtils.setField(asrService, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(asrService, "language", "zh");
        ReflectionTestUtils.setField(asrService, "format", "pcm");
        ReflectionTestUtils.setField(asrService, "sampleRate", 16000);
        ReflectionTestUtils.setField(asrService, "enableTurnDetection", true);
        ReflectionTestUtils.setField(asrService, "turnDetectionType", "server_vad");
        ReflectionTestUtils.setField(asrService, "turnDetectionThreshold", 0.0f);
        ReflectionTestUtils.setField(asrService, "turnDetectionSilenceDurationMs", 400);
    }

    @Test
    @DisplayName("Should initialize service successfully")
    void testInit() {
        assertDoesNotThrow(() -> asrService.init());
    }

    @Test
    @DisplayName("Should start transcription and create session")
    void testStartTranscription() throws Exception {
        asrService.init();

        String sessionId = "test-session-1";
        AtomicReference<String> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        asrService.startTranscription(
            sessionId,
            text -> resultRef.set(text),
            error -> errorRef.set(error)
        );

        // Verify session was created
        assertTrue(asrService.hasActiveSession(sessionId));

        // Cleanup
        asrService.stopTranscription(sessionId);
    }

    @Test
    @DisplayName("Should stop transcription and remove session")
    void testStopTranscription() throws Exception {
        asrService.init();

        String sessionId = "test-session-2";
        CountDownLatch latch = new CountDownLatch(1);

        asrService.startTranscription(
            sessionId,
            text -> {},
            error -> latch.countDown()
        );

        assertTrue(asrService.hasActiveSession(sessionId));

        asrService.stopTranscription(sessionId);

        assertFalse(asrService.hasActiveSession(sessionId));
    }

    @Test
    @DisplayName("Should handle multiple concurrent sessions")
    void testMultipleSessions() throws Exception {
        asrService.init();

        String session1 = "session-1";
        String session2 = "session-2";

        asrService.startTranscription(session1, text -> {}, error -> {});
        asrService.startTranscription(session2, text -> {}, error -> {});

        assertTrue(asrService.hasActiveSession(session1));
        assertTrue(asrService.hasActiveSession(session2));

        asrService.stopTranscription(session1);
        asrService.stopTranscription(session2);

        assertFalse(asrService.hasActiveSession(session1));
        assertFalse(asrService.hasActiveSession(session2));
    }

    @Test
    @DisplayName("Should throw exception when sending audio to non-existent session")
    void testSendAudioToNonExistentSession() {
        asrService.init();

        byte[] audioData = new byte[1024];

        assertThrows(IllegalStateException.class, () -> {
            asrService.sendAudio("non-existent-session", audioData);
        });
    }

    @Test
    @DisplayName("extractTranscriptPayload should concatenate text + stash for partial ASR events")
    void extractTranscriptPayload_textAndStash() {
        JsonObject o = JsonParser.parseString(
                "{\"type\":\"conversation.item.input_audio_transcription.text\",\"text\":\"\",\"stash\":\"北京的\"}"
        ).getAsJsonObject();
        assertEquals("北京的", QwenAsrService.extractTranscriptPayload(o));
    }

    @Test
    @DisplayName("extractTranscriptPayload should merge confirmed prefix and draft suffix")
    void extractTranscriptPayload_mixedPrefixSuffix() {
        JsonObject o = JsonParser.parseString(
                "{\"type\":\"conversation.item.input_audio_transcription.text\",\"text\":\"今天天气不错，\",\"stash\":\"阳光\"}"
        ).getAsJsonObject();
        assertEquals("今天天气不错，阳光", QwenAsrService.extractTranscriptPayload(o));
    }

    @Test
    @DisplayName("Should cleanup resources on destroy")
    void testDestroy() throws Exception {
        asrService.init();

        // Create multiple sessions
        asrService.startTranscription("session-1", text -> {}, error -> {});
        asrService.startTranscription("session-2", text -> {}, error -> {});

        // Destroy should cleanup all sessions
        assertDoesNotThrow(() -> asrService.destroy());

        assertFalse(asrService.hasActiveSession("session-1"));
        assertFalse(asrService.hasActiveSession("session-2"));
    }
}
