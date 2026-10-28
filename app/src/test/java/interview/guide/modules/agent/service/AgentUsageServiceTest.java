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
import interview.guide.modules.agent.repository.AgentRunUsageRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
        when(usageRepository.findByOwnerKeyOrderByCreatedAtDesc("user-1")).thenReturn(List.of(
            usage("user-1", AgentRunStatus.COMPLETED, 100, 50, 150, 2, 2_000, "0.10", "USD", "pixel"),
            usage("user-1", AgentRunStatus.FAILED, 40, 0, 40, 1, 1_000, "0.20", "USD", "pixel"),
            usage("user-1", AgentRunStatus.CANCELLED, 10, 5, 15, 1, 500, null, null, "other")
        ));

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

    private AgentRunUsageEntity usage(
        String owner,
        AgentRunStatus status,
        int prompt,
        int completion,
        int total,
        int calls,
        long duration,
        String cost,
        String currency,
        String provider
    ) {
        return AgentRunUsageEntity.builder()
            .ownerKey(owner)
            .interviewSessionId("interview-1")
            .agentSessionId("agent-" + total)
            .runId("run-" + total)
            .runStatus(status)
            .promptTokens(prompt)
            .completionTokens(completion)
            .totalTokens(total)
            .modelCalls(calls)
            .durationMillis(duration)
            .cost(cost == null ? null : new BigDecimal(cost))
            .currency(currency)
            .provider(provider)
            .createdAt(LocalDateTime.now())
            .build();
    }
}
