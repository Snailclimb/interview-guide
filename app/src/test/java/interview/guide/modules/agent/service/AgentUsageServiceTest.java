package interview.guide.modules.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.model.AgentRunUsage;
import interview.guide.modules.agent.model.AgentRunUsageEntity;
import interview.guide.modules.agent.model.AgentUsageSummaryResponse;
import interview.guide.modules.agent.model.AgentUsageRunResponse;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import interview.guide.modules.agent.repository.AgentRunUsageRepository;
import interview.guide.modules.agent.repository.AgentRunUsageRepository.AgentUsageCostProjection;
import interview.guide.modules.agent.repository.AgentRunUsageRepository.AgentUsageTotalsProjection;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentUsageServiceTest {

    @Mock
    private AgentRunUsageRepository usageRepository;

    @Mock
    private InterviewSessionRepository interviewSessionRepository;

    @Mock
    private AgentChatSessionRepository agentChatSessionRepository;

    @InjectMocks
    private AgentUsageService service;

    @Test
    void recordsOnlyTerminalRunAndIsIdempotentByRunId() {
        LocalDateTime started = LocalDateTime.of(2026, 10, 28, 10, 0);
        LocalDateTime finished = started.plusSeconds(8);
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .ownerKey("user-1")
            .interviewSessionId("interview-1")
            .agentSessionId("agent-1")
            .runId("run-1")
            .runStatus(AgentRunStatus.COMPLETED)
            .runStartedAt(started)
            .runFinishedAt(finished)
            .build();
        AgentRunUsage usage = new AgentRunUsage(
            120, 80, 200, 2, 8_000, new BigDecimal("0.0123"), "USD", "pixel", "gpt-5.6-luna"
        );
        when(usageRepository.findByRunId("run-1")).thenReturn(Optional.empty());

        service.record(session, usage);

        ArgumentCaptor<AgentRunUsageEntity> captor = ArgumentCaptor.forClass(AgentRunUsageEntity.class);
        verify(usageRepository).save(captor.capture());
        AgentRunUsageEntity saved = captor.getValue();
        assertThat(saved.getRunId()).isEqualTo("run-1");
        assertThat(saved.getTotalTokens()).isEqualTo(200);
        assertThat(saved.getCost()).isEqualByComparingTo("0.0123");
        assertThat(saved.getRunStatus()).isEqualTo(AgentRunStatus.COMPLETED);

        AgentRunUsageEntity existing = AgentRunUsageEntity.builder().id(9L).runId("run-1").build();
        when(usageRepository.findByRunId("run-1")).thenReturn(Optional.of(existing));
        service.record(session, usage);
        assertThat(existing.getTotalTokens()).isEqualTo(200);
        verify(usageRepository, org.mockito.Mockito.times(2)).save(any(AgentRunUsageEntity.class));
    }

    @Test
    void summarizesTokensStatusesDurationAndCostsByOwner() {
        AgentUsageTotalsProjection totals = org.mockito.Mockito.mock(AgentUsageTotalsProjection.class);
        when(totals.getPromptTokens()).thenReturn(150L);
        when(totals.getCompletionTokens()).thenReturn(55L);
        when(totals.getTotalTokens()).thenReturn(205L);
        when(totals.getModelCalls()).thenReturn(4L);
        when(totals.getDurationMillis()).thenReturn(3_500L);
        when(usageRepository.summarizeTotals("user-1")).thenReturn(totals);
        when(usageRepository.countByOwnerKey("user-1")).thenReturn(3L);
        when(usageRepository.countByOwnerKeyAndRunStatus("user-1", AgentRunStatus.COMPLETED)).thenReturn(1L);
        when(usageRepository.countByOwnerKeyAndRunStatus("user-1", AgentRunStatus.FAILED)).thenReturn(1L);
        when(usageRepository.countByOwnerKeyAndRunStatus("user-1", AgentRunStatus.CANCELLED)).thenReturn(1L);
        AgentUsageCostProjection costs = org.mockito.Mockito.mock(AgentUsageCostProjection.class);
        when(costs.getProvider()).thenReturn("pixel");
        when(costs.getCurrency()).thenReturn("USD");
        when(costs.getAmount()).thenReturn(new BigDecimal("0.30"));
        when(usageRepository.summarizeCosts("user-1")).thenReturn(List.of(costs));

        AgentUsageSummaryResponse summary = service.summarize("user-1");

        assertThat(summary.runCount()).isEqualTo(3);
        assertThat(summary.completedRunCount()).isEqualTo(1);
        assertThat(summary.failedRunCount()).isEqualTo(1);
        assertThat(summary.cancelledRunCount()).isEqualTo(1);
        assertThat(summary.promptTokens()).isEqualTo(150);
        assertThat(summary.completionTokens()).isEqualTo(55);
        assertThat(summary.totalTokens()).isEqualTo(205);
        assertThat(summary.modelCalls()).isEqualTo(4);
        assertThat(summary.durationMillis()).isEqualTo(3_500);
        assertThat(summary.costs()).singleElement().satisfies(cost -> {
            assertThat(cost.provider()).isEqualTo("pixel");
            assertThat(cost.currency()).isEqualTo("USD");
            assertThat(cost.amount()).isEqualByComparingTo("0.30");
        });
    }

    @Test
    void ignoresNonTerminalRunsAndBlankOwner() {
        AgentChatSessionEntity running = AgentChatSessionEntity.builder()
            .ownerKey("user-1").interviewSessionId("i-1").agentSessionId("a-1")
            .runId("run-running").runStatus(AgentRunStatus.RUNNING).build();
        service.record(running, AgentRunUsage.empty());
        assertThat(service.summarize(" ").runCount()).isZero();
        org.mockito.Mockito.verifyNoInteractions(usageRepository);
    }

    @Test
    void listsRecentRunsWithInterviewAndAgentSessionNames() {
        AgentRunUsageEntity run = AgentRunUsageEntity.builder()
            .ownerKey("user-1")
            .interviewSessionId("interview-1")
            .agentSessionId("agent-1")
            .runId("run-1")
            .runStatus(AgentRunStatus.COMPLETED)
            .promptTokens(120)
            .completionTokens(80)
            .totalTokens(200)
            .modelCalls(2)
            .durationMillis(8_000)
            .provider("pixel")
            .model("gpt-5.6-luna")
            .build();
        InterviewSessionEntity interview = new InterviewSessionEntity();
        interview.setSessionId("interview-1");
        interview.setTitle("Java 后端高并发面试");
        interview.setSkillId("java-backend");
        AgentChatSessionEntity agentSession = AgentChatSessionEntity.builder()
            .agentSessionId("agent-1")
            .title("第一轮复盘")
            .ownerKey("user-1")
            .build();
        when(usageRepository.findTop100ByOwnerKeyOrderByCreatedAtDesc("user-1"))
            .thenReturn(List.of(run));
        when(interviewSessionRepository.findAllBySessionIdInAndOwnerId(Set.of("interview-1"), "user-1"))
            .thenReturn(List.of(interview));
        when(agentChatSessionRepository.findAllByAgentSessionIdInAndOwnerKey(Set.of("agent-1"), "user-1"))
            .thenReturn(List.of(agentSession));

        List<AgentUsageRunResponse> runs = service.listRuns(" user-1 ", 20);

        assertThat(runs).singleElement().satisfies(item -> {
            assertThat(item.interviewTitle()).isEqualTo("Java 后端高并发面试");
            assertThat(item.agentSessionTitle()).isEqualTo("第一轮复盘");
            assertThat(item.totalTokens()).isEqualTo(200);
            assertThat(item.model()).isEqualTo("gpt-5.6-luna");
        });
    }
}
