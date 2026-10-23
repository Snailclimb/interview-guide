package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunRecoveryServiceTest {

    @Test
    void onlyClaimsPersistedRunningTasksOlderThanRecoveryFence() {
        AgentChatSessionRepository repository = mock(AgentChatSessionRepository.class);
        AgentConversationService conversation = mock(AgentConversationService.class);
        InterviewAgentService agent = mock(InterviewAgentService.class);
        AgentRetryProperties properties = new AgentRetryProperties();
        properties.setRecoveryMinAgeSeconds(30);
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .interviewSessionId("session-1")
            .runId("old-run")
            .runStatus(AgentRunStatus.RUNNING)
            .runStartedAt(LocalDateTime.now().minusMinutes(2))
            .build();
        when(repository.findByRunStatusAndRunStartedAtBefore(eq(AgentRunStatus.RUNNING), any()))
            .thenReturn(List.of(session));
        when(conversation.claimRecoverableRun(eq("session-1"), any()))
            .thenReturn(new AgentConversationService.RecoveredRun(
                "session-1", "review", "provider", "memory", "请恢复", 10L, "new-run"
            ));

        new AgentRunRecoveryService(repository, conversation, agent, properties).recoverInterruptedRuns();

        verify(conversation).claimRecoverableRun(eq("session-1"), any());
        verify(agent).recoverRun(any(AgentConversationService.RecoveredRun.class));
    }

    @Test
    void canDisableRecoveryWithoutTouchingRepository() {
        AgentChatSessionRepository repository = mock(AgentChatSessionRepository.class);
        AgentRetryProperties properties = new AgentRetryProperties();
        properties.setRecoveryEnabled(false);

        new AgentRunRecoveryService(
            repository, mock(AgentConversationService.class), mock(InterviewAgentService.class), properties
        ).recoverInterruptedRuns();

        verify(repository, never()).findByRunStatusAndRunStartedAtBefore(any(), any());
    }
}
