package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopEngineTest {

    @Test
    void executesEveryBoundedStepAndCarriesObservations() {
        AgentContextService.AgentContext context = new AgentContextService.AgentContext(
            "report", "java-backend", "skill", "rag",
            "query", List.of(new AgentSource("skill", "Java Skill", null, "S1")),
            "memory", List.of("Redis")
        );

        AgentLoopEngine.LoopResult result = new AgentLoopEngine().execute(
            context, List.of("读取报告", "核对 Skill", "给出建议")
        );

        assertThat(result.iterations()).hasSize(3);
        assertThat(result.iterations().get(0).observation()).contains("1 个待关注项");
        assertThat(result.iterations().get(1).observation()).contains("1 个可引用来源");
        assertThat(result.trace()).contains("第 3 步：给出建议");
    }

    @Test
    void explicitlyMarksMissingEvidenceWithoutInventingSources() {
        AgentContextService.AgentContext context = new AgentContextService.AgentContext(
            "report", "java-backend", "skill", "rag", "query", List.of(), "memory", List.of()
        );

        AgentLoopEngine.LoopResult result = new AgentLoopEngine().execute(
            context, List.of("读取报告", "核对资料")
        );

        assertThat(result.iterations().get(1).observation()).contains("不得声称使用了外部依据");
    }

    @Test
    void retriesFailedStepWithinStepBudget() {
        AtomicInteger attempts = new AtomicInteger();
        AgentLoopEngine.LoopResult result = new AgentLoopEngine().execute(
            context(),
            List.of("读取报告"),
            new AgentLoopEngine.ExecutionPolicy(1, 1, 1_000, 3_000),
            (ignored, action, index) -> attempts.incrementAndGet() == 1
                ? throwFailure()
                : "已完成 " + action
        );

        assertThat(result.completed()).isTrue();
        assertThat(result.iterations().getFirst().attempts()).isEqualTo(2);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void stopsTimedOutStepAndLeavesItAvailableForResume() {
        AgentLoopEngine.LoopResult result = new AgentLoopEngine().execute(
            context(),
            List.of("慢步骤"),
            new AgentLoopEngine.ExecutionPolicy(1, 0, 50, 200),
            (ignored, action, index) -> {
                Thread.sleep(500);
                return action;
            }
        );

        assertThat(result.completed()).isFalse();
        assertThat(result.termination()).isEqualTo("step_timeout");
        assertThat(result.nextStepIndex()).isZero();
        assertThat(result.iterations().getFirst().status()).isEqualTo("timeout");
    }

    @Test
    void resumesFromFirstIncompleteStepWithoutRepeatingCompletedStep() {
        AgentLoopEngine engine = new AgentLoopEngine();
        AgentLoopEngine.LoopResult first = engine.execute(
            context(),
            List.of("第一步", "第二步", "第三步"),
            new AgentLoopEngine.ExecutionPolicy(3, 0, 1_000, 3_000),
            (ignored, action, index) -> index == 1 ? throwFailure() : "完成 " + action
        );

        AgentLoopEngine.LoopResult resumed = engine.resume(
            context(),
            List.of("第一步", "第二步", "第三步"),
            first,
            new AgentLoopEngine.ExecutionPolicy(3, 0, 1_000, 3_000),
            (ignored, action, index) -> "恢复完成 " + action
        );

        assertThat(first.nextStepIndex()).isEqualTo(1);
        assertThat(resumed.iterations()).hasSize(2);
        assertThat(resumed.iterations().getFirst().iteration()).isEqualTo(2);
        assertThat(resumed.completed()).isTrue();
    }

    private AgentContextService.AgentContext context() {
        return new AgentContextService.AgentContext(
            "report", "java-backend", "skill", "rag",
            "query", List.of(new AgentSource("skill", "Java Skill", null, "S1")),
            "memory", List.of("Redis")
        );
    }

    private String throwFailure() {
        throw new IllegalStateException("transient");
    }
}
