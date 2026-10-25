package interview.guide.modules.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Agent 的显式有限状态循环。
 *
 * <p>这里的动作是可审计的上下文动作，不执行写操作，也不把隐藏推理暴露给用户：
 * 读取报告、确认 Skill/RAG 证据、准备最终行动建议。每一步都产生观察结果，
 * 观察结果会进入最终回答上下文；步骤数由规划器限制，避免无限循环。</p>
 */
public class AgentLoopEngine {

    public LoopResult execute(AgentContextService.AgentContext context, List<String> plannedSteps) {
        int stepCount = plannedSteps == null ? 1 : Math.max(1, plannedSteps.size());
        return execute(
            context,
            plannedSteps,
            ExecutionPolicy.defaults(stepCount),
            null
        );
    }

    /**
     * 执行受控 Loop。StepExecutor 让未来的只读工具步骤可以复用相同的预算、超时和
     * 重试边界；默认面试流程仍使用本地 observe，不会增加额外模型调用。
     */
    public LoopResult execute(
        AgentContextService.AgentContext context,
        List<String> plannedSteps,
        ExecutionPolicy policy,
        StepExecutor stepExecutor
    ) {
        return execute(context, plannedSteps, policy, stepExecutor, null);
    }

    /** 执行 Loop，并在每个步骤产生结果后通知检查点持久化器。 */
    public LoopResult execute(
        AgentContextService.AgentContext context,
        List<String> plannedSteps,
        ExecutionPolicy policy,
        StepExecutor stepExecutor,
        Consumer<LoopIteration> progressListener
    ) {
        return executeFrom(context, plannedSteps, policy, stepExecutor, 0, progressListener);
    }

    /**
     * 从上一个已完成步骤之后继续执行。调用方可以把上一次结果持久化后，在恢复任务时
     * 传回来；失败步骤会从原位置重新执行，不重复已经完成的观察。
     */
    public LoopResult resume(
        AgentContextService.AgentContext context,
        List<String> plannedSteps,
        LoopResult previous,
        ExecutionPolicy policy,
        StepExecutor stepExecutor
    ) {
        return resume(context, plannedSteps, previous, policy, stepExecutor, null);
    }

    public LoopResult resume(
        AgentContextService.AgentContext context,
        List<String> plannedSteps,
        LoopResult previous,
        ExecutionPolicy policy,
        StepExecutor stepExecutor,
        Consumer<LoopIteration> progressListener
    ) {
        int startIndex = previous == null ? 0 : previous.nextStepIndex();
        return executeFrom(
            context, plannedSteps, policy, stepExecutor, startIndex, progressListener
        );
    }

    private LoopResult executeFrom(
        AgentContextService.AgentContext context,
        List<String> plannedSteps,
        ExecutionPolicy policy,
        StepExecutor stepExecutor,
        int startIndex,
        Consumer<LoopIteration> progressListener
    ) {
        List<String> safeSteps = plannedSteps == null || plannedSteps.isEmpty()
            ? List.of("读取面试报告并确认当前目标")
            : plannedSteps;
        ExecutionPolicy safePolicy = policy == null
            ? ExecutionPolicy.defaults(safeSteps.size())
            : policy;
        boolean useDefaultExecutor = stepExecutor == null;
        StepExecutor safeExecutor = useDefaultExecutor
            ? (loopContext, action, index) -> defaultObservation(loopContext, index)
            : stepExecutor;
        List<LoopIteration> iterations = new ArrayList<>();
        int first = Math.max(0, Math.min(startIndex, safeSteps.size()));
        int last = Math.min(safeSteps.size(), first + safePolicy.maxSteps());
        long startedNanos = System.nanoTime();
        String termination = "completed";
        int nextStepIndex = first;
        for (int index = first; index < last; index++) {
            String step = safeSteps.get(index);
            long remainingMillis = safePolicy.remainingMillis(startedNanos);
            if (remainingMillis <= 0) {
                termination = "total_timeout";
                break;
            }
            int attempts = 0;
            String observation = null;
            Throwable lastError = null;
            long stepStartedNanos = System.nanoTime();
            int maxAttempts = safePolicy.maxStepRetries() + 1;
            while (attempts < maxAttempts) {
                attempts++;
                try {
                    long timeoutMillis = Math.min(safePolicy.stepTimeoutMillis(), remainingMillis);
                    observation = useDefaultExecutor
                        ? defaultObservation(context, index)
                        : executeWithTimeout(safeExecutor, context, step, index, timeoutMillis);
                    if (observation == null || observation.isBlank()) {
                        throw new IllegalStateException("empty_observation");
                    }
                    break;
                } catch (Throwable error) {
                    lastError = error;
                    remainingMillis = safePolicy.remainingMillis(startedNanos);
                    if (remainingMillis <= 0 || attempts >= maxAttempts) {
                        break;
                    }
                }
            }
            if (observation == null || observation.isBlank()) {
                String status = lastError instanceof LoopStepTimeoutException
                    ? "timeout" : "failed";
                String errorName = lastError == null
                    ? "unknown" : lastError.getClass().getSimpleName();
                LoopIteration failedIteration = new LoopIteration(
                    index + 1,
                    step,
                    "步骤执行失败（" + errorName + "）",
                    status,
                    attempts,
                    elapsedMillis(stepStartedNanos),
                    failureReason(lastError)
                );
                iterations.add(failedIteration);
                notifyProgress(progressListener, failedIteration);
                termination = "timeout".equals(status) ? "step_timeout" : "step_failed";
                nextStepIndex = index;
                break;
            }
            LoopIteration completedIteration = new LoopIteration(
                index + 1, step, observation, "completed", attempts,
                elapsedMillis(stepStartedNanos), null
            );
            iterations.add(completedIteration);
            notifyProgress(progressListener, completedIteration);
            nextStepIndex = index + 1;
        }
        if ("completed".equals(termination) && nextStepIndex < safeSteps.size()) {
            termination = "step_budget";
        }
        if ("completed".equals(termination) && safeSteps.isEmpty()) {
            termination = "empty_plan";
        }
        return new LoopResult(List.copyOf(iterations), termination, nextStepIndex);
    }

    private void notifyProgress(Consumer<LoopIteration> progressListener, LoopIteration iteration) {
        if (progressListener == null) {
            return;
        }
        try {
            progressListener.accept(iteration);
        } catch (Throwable ignored) {
            // 检查点是附加能力，不能因为写入进度失败而改变 Agent 主流程结果。
        }
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    private String failureReason(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String type = error.getClass().getSimpleName();
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return type;
        }
        String clean = message.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return (type + ": " + clean).substring(0, Math.min(160, type.length() + 2 + clean.length()));
    }

    private String executeWithTimeout(
        StepExecutor executor,
        AgentContextService.AgentContext context,
        String action,
        int index,
        long timeoutMillis
    ) throws Exception {
        if (timeoutMillis <= 0) {
            throw new LoopStepTimeoutException();
        }
        Future<String> future = LOOP_EXECUTOR.submit(() -> executor.execute(context, action, index));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new LoopStepTimeoutException();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException("loop_step_failed", cause);
        }
    }

    public static String defaultObservation(AgentContextService.AgentContext context, int index) {
        return switch (index) {
            case 0 -> "已读取面试报告；识别到 " + context.weaknesses().size() + " 个待关注项。";
            case 1 -> context.sources().isEmpty()
                ? "Skill/RAG 未提供可引用来源，本轮回答不得声称使用了外部依据。"
                : "已核对 Skill/RAG 来源，共 " + context.sources().size() + " 个可引用来源。";
            default -> "已将报告事实、证据边界和用户目标整理为可执行建议。";
        };
    }

    public record LoopIteration(
        int iteration,
        String action,
        String observation,
        String status,
        int attempts,
        long durationMillis,
        String failureReason
    ) {
        public LoopIteration(int iteration, String action, String observation) {
            this(iteration, action, observation, "completed", 1, 0, null);
        }

        public LoopIteration(
            int iteration, String action, String observation, String status, int attempts
        ) {
            this(iteration, action, observation, status, attempts, 0, null);
        }
    }

    public record LoopResult(List<LoopIteration> iterations, String termination, int nextStepIndex) {
        public LoopResult(List<LoopIteration> iterations) {
            this(iterations, "completed", iterations == null ? 0 : iterations.size());
        }

        public LoopResult {
            iterations = iterations == null ? List.of() : List.copyOf(iterations);
            termination = termination == null || termination.isBlank() ? "unknown" : termination;
            nextStepIndex = Math.max(0, nextStepIndex);
        }

        public String trace() {
            return iterations.stream()
                .map(item -> "第 " + item.iteration() + " 步：" + item.action()
                    + "；观察：" + item.observation()
                    + "；耗时：" + item.durationMillis() + "ms"
                    + (item.failureReason() == null ? "" : "；失败原因：" + item.failureReason())
                    + ("completed".equals(item.status()) ? "" : "；状态：" + item.status()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("未产生 Loop 观察结果");
        }

        public boolean completed() {
            return "completed".equals(termination);
        }
    }

    @FunctionalInterface
    public interface StepExecutor {
        String execute(AgentContextService.AgentContext context, String action, int index) throws Exception;
    }

    public record ExecutionPolicy(
        int maxSteps,
        int maxStepRetries,
        long stepTimeoutMillis,
        long totalTimeoutMillis
    ) {
        private static final long DEFAULT_STEP_TIMEOUT_MILLIS = 5_000;
        private static final long DEFAULT_TOTAL_TIMEOUT_MILLIS = 15_000;

        public ExecutionPolicy {
            maxSteps = Math.max(1, maxSteps);
            maxStepRetries = Math.max(0, maxStepRetries);
            stepTimeoutMillis = Math.max(1, stepTimeoutMillis);
            totalTimeoutMillis = Math.max(stepTimeoutMillis, totalTimeoutMillis);
        }

        public static ExecutionPolicy defaults(int maxSteps) {
            return new ExecutionPolicy(
                maxSteps, 0, DEFAULT_STEP_TIMEOUT_MILLIS, DEFAULT_TOTAL_TIMEOUT_MILLIS
            );
        }

        private long remainingMillis(long startedNanos) {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            return totalTimeoutMillis - elapsedMillis;
        }
    }

    private static final ExecutorService LOOP_EXECUTOR = Executors.newFixedThreadPool(
        4,
        new LoopThreadFactory()
    );

    private static final class LoopThreadFactory implements ThreadFactory {
        private int sequence;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agent-loop-step-" + (++sequence));
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class LoopStepTimeoutException extends RuntimeException {
    }
}
