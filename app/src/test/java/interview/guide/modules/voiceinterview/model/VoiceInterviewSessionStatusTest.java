package interview.guide.modules.voiceinterview.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoiceInterviewSessionStatusTest {

    @Test
    void shouldHaveFourStatuses() {
        VoiceInterviewSessionStatus[] statuses = VoiceInterviewSessionStatus.values();
        assertEquals(4, statuses.length);
    }

    @Test
    void shouldHaveExpectedStatusNames() {
        assertEquals("IN_PROGRESS", VoiceInterviewSessionStatus.IN_PROGRESS.name());
        assertEquals("PAUSED", VoiceInterviewSessionStatus.PAUSED.name());
        assertEquals("COMPLETED", VoiceInterviewSessionStatus.COMPLETED.name());
        assertEquals("FAILED", VoiceInterviewSessionStatus.FAILED.name());
    }
}
