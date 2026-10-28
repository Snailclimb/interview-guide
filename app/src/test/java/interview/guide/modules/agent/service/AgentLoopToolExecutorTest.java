package interview.guide.modules.agent.service;

import interview.guide.modules.agent.metrics.AgentMetrics;
import interview.guide.modules.agent.metrics.AgentRunBudgetExceededException;
import interview.guide.modules.agent.model.AgentSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopToolExecutorTest {

    @Test
    void executesReadOnlyToolsWithExplicitCanonicalNames() {
        AgentContextService.AgentContext context = context();
        AgentLoopToolExecutor executor = new AgentLoopToolExecutor(null, null, null);

        assertThat(executor.execute(context, "读取面试报告", 0))
            .contains("2 个待关注项");
        assertThat(executor.execute(context, "确认当前目标", 1))
            .contains("java-backend");
        assertThat(executor.execute(context, "核对 Skill/RAG 来源", 2))
            .contains("Skill 1 个", "知识库 1 个");
    }

    @Test
    void missingSourcesAreExplicitlyReportedWithoutInventingEvidence() {
        AgentContextService.AgentContext context = new AgentContextService.AgentContext(
            "report", "java-backend", "skill", "rag", "query", List.of(), "memory", List.of()
        );

        String observation = new AgentLoopToolExecutor(null, null, null)
            .execute(context, "核对 Skill/RAG 来源", 1);

        assertThat(observation).contains("不得声称使用了外部依据");
    }

    @Test
    void toolBudgetIsEnforcedByTheRunTracker() {
        AgentMetrics.RunTracker tracker = new AgentMetrics(null, false).startRun(4, 1);
        AgentLoopToolExecutor executor = new AgentLoopToolExecutor(null, null, tracker);

        executor.execute(context(), "读取面试报告", 0);

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> executor.execute(context(), "确认当前目标", 1)
        ).isInstanceOf(AgentRunBudgetExceededException.class);
    }

    @Test
    void canonicalNameFallsBackToSafeReadOnlyTool() {
        assertThat(AgentLoopToolExecutor.resolveToolName("未知步骤", 0))
            .isEqualTo(AgentLoopToolExecutor.READ_INTERVIEW_REPORT);
        assertThat(AgentLoopToolExecutor.resolveToolName("未知步骤", 2))
            .isEqualTo(AgentLoopToolExecutor.ORGANIZE_AGENT_CONTEXT);
    }

    @Test
    void recordsOnlyLowSensitivityToolIdentityInAudit() {
        AgentMetrics metrics = new AgentMetrics(null, false);
        AgentMetrics.RunTracker tracker = metrics.startRun(4, 2);
        AgentAuditService auditService = mock(AgentAuditService.class);
        metrics.setAuditContext(tracker, "owner-1", "interview-1", "agent-1", "run-1");

        new AgentLoopToolExecutor(metrics, auditService, tracker)
            .execute(context(), "读取面试报告", 0);

        verify(auditService).recordQuietly(
            "owner-1", "interview-1", "agent-1", "run-1",
            "TOOL_CALLED", "agent-loop-tool", AgentLoopToolExecutor.READ_INTERVIEW_REPORT, "success"
        );
    }

    private AgentContextService.AgentContext context() {
        return new AgentContextService.AgentContext(
            "report", "java-backend", "skill", "rag", "query",
            List.of(
                new AgentSource("skill", "Java Skill", null, "S1"),
                new AgentSource("rag", "Redis Guide", 0.91, "R1")
            ),
            "memory", List.of("Redis", "MQ")
        );
    }
}
