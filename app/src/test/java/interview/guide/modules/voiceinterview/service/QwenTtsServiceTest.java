package interview.guide.modules.voiceinterview.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QwenTtsService Unit Tests")
class QwenTtsServiceTest {

    private QwenTtsService ttsService;

    @BeforeEach
    void setUp() {
        ttsService = new QwenTtsService();

        // Set field values using reflection
        ReflectionTestUtils.setField(ttsService, "url", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime");
        ReflectionTestUtils.setField(ttsService, "model", "qwen3-tts-flash-realtime");
        ReflectionTestUtils.setField(ttsService, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(ttsService, "voice", "Cherry");
        ReflectionTestUtils.setField(ttsService, "format", "pcm");
        ReflectionTestUtils.setField(ttsService, "sampleRate", 16000);
        ReflectionTestUtils.setField(ttsService, "mode", "server_commit");
        ReflectionTestUtils.setField(ttsService, "languageType", "Chinese");
        ReflectionTestUtils.setField(ttsService, "speechRate", 1.0f);
        ReflectionTestUtils.setField(ttsService, "volume", 60);
    }

    @Test
    @DisplayName("Should initialize service successfully")
    void testInit() {
        assertDoesNotThrow(() -> ttsService.init());
    }

    @Test
    @DisplayName("Should return empty array for empty text")
    void testSynthesizeEmptyText() {
        ttsService.init();

        byte[] result = ttsService.synthesize("");

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should return empty array for null text")
    void testSynthesizeNullText() {
        ttsService.init();

        byte[] result = ttsService.synthesize(null);

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should return empty array for whitespace text")
    void testSynthesizeWhitespaceText() {
        ttsService.init();

        byte[] result = ttsService.synthesize("   ");

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should cleanup resources on destroy")
    void testDestroy() {
        ttsService.init();

        // Destroy should cleanup resources without error
        assertDoesNotThrow(() -> ttsService.destroy());
    }
}
