package interview.guide.modules.voiceinterview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.voiceinterview.dto.SessionMetaDTO;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VoiceInterviewServicePauseTest {

    @Mock
    private VoiceInterviewSessionRepository sessionRepository;

    @Mock
    private VoiceInterviewMessageRepository messageRepository;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RBucket<Object> bucket;

    private VoiceInterviewService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new VoiceInterviewService(
            sessionRepository,
            messageRepository,
            redissonClient,
            null  // properties
        );
    }

    @Test
    void pauseSession_shouldSaveWithPausedStatus() {
        // Given
        Long sessionId = 1L;
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(sessionId)
            .status(VoiceInterviewSessionStatus.IN_PROGRESS)
            .build();

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(redissonClient.getBucket(anyString())).thenReturn(bucket);

        // When
        service.pauseSession(sessionId.toString(), "user_initiated");

        // Then
        verify(sessionRepository).save(argThat(s ->
            s.getStatus() == VoiceInterviewSessionStatus.PAUSED &&
            s.getPausedAt() != null
        ));
    }

    @Test
    void pauseSession_shouldThrowWhenNotInProgress() {
        // Given
        Long sessionId = 1L;
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(sessionId)
            .status(VoiceInterviewSessionStatus.COMPLETED)
            .build();

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // When & Then
        assertThrows(BusinessException.class, () ->
            service.pauseSession(sessionId.toString(), "user_initiated")
        );
    }

    @Test
    void resumeSession_shouldReturnWebSocketUrl() {
        // Given
        Long sessionId = 1L;
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(sessionId)
            .status(VoiceInterviewSessionStatus.PAUSED)
            .roleType("ali-p8")
            .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.TECH)
            .plannedDuration(30)
            .build();

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(redissonClient.getBucket(anyString())).thenReturn(bucket);

        // When
        SessionResponseDTO result = service.resumeSession(sessionId.toString());

        // Then
        assertNotNull(result);
        assertEquals(sessionId, result.getSessionId());
        assertTrue(result.getWebSocketUrl().contains(sessionId.toString()));
        assertEquals(VoiceInterviewSessionStatus.IN_PROGRESS.name(), result.getStatus());
    }

    @Test
    void resumeSession_shouldThrowWhenNotPaused() {
        // Given
        Long sessionId = 1L;
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(sessionId)
            .status(VoiceInterviewSessionStatus.IN_PROGRESS)
            .build();

        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        // When & Then
        assertThrows(BusinessException.class, () ->
            service.resumeSession(sessionId.toString())
        );
    }
}
