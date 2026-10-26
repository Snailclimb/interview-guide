package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentSource;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
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

    @Test
    void notifiesCheckpointAfterEachCompletedStepAndFailedStep() {
        List<AgentLoopEngine.LoopIteration> checkpoints = new ArrayList<>();
        AgentLoopEngine.LoopResult result = new AgentLoopEngine().execute(
            context(),
            List.of("第一步", "第二步"),
            new AgentLoopEngine.ExecutionPolicy(2, 0, 1_000, 3_000),
            (ignored, action, index) -> index == 1 ? throwFailure() : "完成 " + action,
            checkpoints::add
        );

        assertThat(result.termination()).isEqualTo("step_failed");
        assertThat(checkpoints).hasSize(2);
        assertThat(checkpoints.get(0).status()).isEqualTo("completed");
        assertThat(checkpoints.get(1).status()).isEqualTo("failed");
        assertThat(checkpoints.get(0).durationMillis()).isGreaterThanOrEqualTo(0);
        assertThat(checkpoints.get(1).failureReason()).contains("IllegalStateException");
        assertThat(result.trace()).contains("耗时：").contains("失败原因：");
        assertThat(result.nextStepIndex()).isEqualTo(1);
    }

    @Test
    void runsOnlyExplicitlyMarkedAdjacentStepsInParallel() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AgentLoopEngine.LoopResult result = new AgentLoopEngine().execute(
            context(),
            List.of("[parallel] 核对 Skill", "[parallel] 核对 RAG", "汇总建议"),
            new AgentLoopEngine.ExecutionPolicy(3, 0, 1_000, 3_000, 2),
            (ignored, action, index) -> {
                if (index < 2) {
                    barrier.await(500, TimeUnit.MILLISECONDS);
                }
                return "已完成 " + action;
            }
        );

        assertThat(result.completed()).isTrue();
        assertThat(result.iterations()).extracting(AgentLoopEngine.LoopIteration::action)
            .containsExactly("核对 Skill", "核对 RAG", "汇总建议");
        assertThat(result.iterations().get(0).status()).isEqualTo("completed");
    }

    @Test
    void parallelFailureKeepsResumeIndexAtFirstFailedStep() {
        AgentLoopEngine.LoopResult result = new AgentLoopEngine().execute(
            context(),
            List.of("[parallel] 成功读取", "[parallel] 失败读取", "汇总建议"),
            new AgentLoopEngine.ExecutionPolicy(3, 0, 1_000, 3_000, 2),
            (ignored, action, index) -> index == 1 ? throwFailure() : "完成 " + action
        );

        assertThat(result.completed()).isFalse();
        assertThat(result.termination()).isEqualTo("step_failed");
        assertThat(result.nextStepIndex()).isEqualTo(1);
        assertThat(result.iterations()).hasSize(2);
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
