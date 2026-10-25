package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.auth.AuthUserContext;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class VoiceInterviewOwnershipTest {

    @Mock private VoiceInterviewSessionRepository sessionRepository;
    @Mock private VoiceInterviewMessageRepository messageRepository;
    @Mock private VoiceInterviewEvaluationRepository evaluationRepository;
    @Mock private RedissonClient redissonClient;
    @Mock private VoiceInterviewProperties properties;
    @Mock private VoiceEvaluateStreamProducer evaluateStreamProducer;
    @Mock private interview.guide.common.ai.LlmProviderRegistry llmProviderRegistry;
    @Mock private ResumeRepository resumeRepository;
    @Mock private RBucket<VoiceInterviewSessionEntity> sessionBucket;

    private VoiceInterviewService service;

    @BeforeEach
    void setUp() {
        service = new VoiceInterviewService(
            sessionRepository,
            messageRepository,
            evaluationRepository,
            redissonClient,
            properties,
            evaluateStreamProducer,
            llmProviderRegistry,
            resumeRepository
        );
        lenient().when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(sessionBucket);
        lenient().when(sessionBucket.get()).thenReturn(null);
        AuthUserContext.set("user-a");
    }

    @AfterEach
    void tearDown() {
        AuthUserContext.clear();
    }

    @Test
    void cacheEntryForAnotherUserIsNotReturned() {
        VoiceInterviewSessionEntity cached = session("user-b");
        when(sessionBucket.get()).thenReturn(cached);
        VoiceInterviewSessionEntity owned = session("user-a");
        when(sessionRepository.findByIdAndUserId(7L, "user-a")).thenReturn(Optional.of(owned));

        assertThat(service.getSession(7L)).isSameAs(owned);
        verify(sessionRepository).findByIdAndUserId(7L, "user-a");
    }

    @Test
    void listIgnoresRequestedUserId() {
        when(sessionRepository.findByUserIdOrderByUpdatedAtDesc("user-a"))
            .thenReturn(List.of());

        assertThat(service.getAllSessions("user-b", null)).isEmpty();
        verify(sessionRepository).findByUserIdOrderByUpdatedAtDesc("user-a");
    }

    @Test
    void deleteUsesAuthenticatedOwner() {
        VoiceInterviewSessionEntity owned = session("user-a");
        when(sessionRepository.findByIdAndUserId(7L, "user-a")).thenReturn(Optional.of(owned));

        service.deleteSession(7L);

        verify(sessionRepository).delete(owned);
    }

    private VoiceInterviewSessionEntity session(String ownerId) {
        return VoiceInterviewSessionEntity.builder()
            .id(7L)
            .userId(ownerId)
            .roleType("java-backend")
            .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
            .status(VoiceInterviewSessionStatus.IN_PROGRESS)
            .build();
    }
}
