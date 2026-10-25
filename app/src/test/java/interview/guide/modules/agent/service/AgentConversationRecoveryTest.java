package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatMessageEntity;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.repository.AgentChatMessageRepository;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentConversationRecoveryTest {

    @Test
    void claimsStaleRunAndResetsAssistantPlaceholderWithoutDuplicatingMessages() {
        AgentChatSessionRepository sessions = mock(AgentChatSessionRepository.class);
        AgentChatMessageRepository messages = mock(AgentChatMessageRepository.class);
        AgentConversationService service = new AgentConversationService(
            sessions, messages, mock(ObjectMapper.class), mock(InterviewPersistenceService.class)
        );
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .id(1L)
            .interviewSessionId("session-1")
            .goal("review")
            .providerId("provider")
            .memoryKey("browser")
            .runId("old-run")
            .runStatus(AgentRunStatus.RUNNING)
            .activeMessageId(3L)
            .runStartedAt(LocalDateTime.now().minusMinutes(2))
            .build();
        AgentChatMessageEntity user = AgentChatMessageEntity.builder()
            .id(2L).session(session).role(AgentChatMessageEntity.MessageRole.USER)
            .content("请恢复这次复盘").sequenceNum(0).completed(true).build();
        AgentChatMessageEntity assistant = AgentChatMessageEntity.builder()
            .id(3L).session(session).role(AgentChatMessageEntity.MessageRole.ASSISTANT)
            .content("半截答案").sequenceNum(1).completed(false).build();
        when(sessions.findByInterviewSessionIdForUpdate("session-1")).thenReturn(Optional.of(session));
        when(messages.findById(3L)).thenReturn(Optional.of(assistant));
        when(messages.findBySessionIdAndCompletedTrueOrderBySequenceNumAsc(1L)).thenReturn(List.of(user));
        when(messages.save(any(AgentChatMessageEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessions.save(any(AgentChatSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AgentConversationService.RecoveredRun recovered = service.claimRecoverableRun(
            "session-1", LocalDateTime.now().minusSeconds(30)
        );

        assertThat(recovered).isNotNull();
        assertThat(recovered.runId()).isNotEqualTo("old-run");
        assertThat(recovered.userContent()).isEqualTo("请恢复这次复盘");
        assertThat(assistant.getContent()).isEmpty();
        assertThat(assistant.getCompleted()).isFalse();
        assertThat(session.getRunNotice()).contains("自动恢复");
    }
}
