package interview.guide.modules.agent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
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
        return executeFrom(
            context, plannedSteps, policy, stepExecutor, 0, List.of(), progressListener
        );
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
        return resume(
            context, plannedSteps, previous, List.of(), policy, stepExecutor, progressListener
        );
    }

    /**
     * 从持久化检查点恢复；completedStepIndices 用于记录并行组中已完成但位于失败步骤之后的步骤。
     */
    public LoopResult resume(
        AgentContextService.AgentContext context,
        List<String> plannedSteps,
        LoopResult previous,
        List<Integer> completedStepIndices,
        ExecutionPolicy policy,
        StepExecutor stepExecutor,
        Consumer<LoopIteration> progressListener
    ) {
        int startIndex = previous == null ? 0 : previous.nextStepIndex();
        return executeFrom(
            context, plannedSteps, policy, stepExecutor, startIndex,
            mergeCompletedStepIndices(
                previous == null ? List.of() : previous.iterations(), completedStepIndices
            ), progressListener
        );
    }

    private LoopResult executeFrom(
        AgentContextService.AgentContext context,
        List<String> plannedSteps,
        ExecutionPolicy policy,
        StepExecutor stepExecutor,
        int startIndex,
        List<Integer> recoveredCompletedStepIndices,
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
            ? (loopContext, action, index) -> defaultObservation(loopContext, action, index)
            : stepExecutor;
        List<LoopIteration> iterations = new ArrayList<>();
        boolean checkpointIndexValid = startIndex >= 0 && startIndex <= safeSteps.size();
        // 运行记录是持久化数据，异常索引不能被解释成“所有步骤都已完成”。
        // 安全回退到计划起点，避免恢复任务误报完成。
        int first = checkpointIndexValid ? startIndex : 0;
        List<Integer> recoveredCompletedSteps = checkpointIndexValid
            ? sanitizeCompletedStepIndices(recoveredCompletedStepIndices, safeSteps.size())
            : List.of();
        int last = Math.min(safeSteps.size(), first + safePolicy.maxSteps());
        long startedNanos = System.nanoTime();
        String termination = "completed";
        int nextStepIndex = first;
        int index = first;
        while (index < last) {
            if (recoveredCompletedSteps.contains(index)) {
                nextStepIndex = Math.max(nextStepIndex, index + 1);
                index++;
                continue;
            }
            int groupEnd = parallelGroupEnd(safeSteps, index, last, safePolicy.maxParallelSteps());
            if (groupEnd - index > 1) {
                ParallelGroupResult group = executeParallelGroup(
                    context, safeSteps, index, groupEnd, safePolicy, startedNanos, safeExecutor,
                    recoveredCompletedSteps
                );
                iterations.addAll(group.iterations());
                if (group.failed()) {
                    // 成功的并行步骤也要先落检查点；恢复只能从最早失败步骤继续。
                    group.iterations().forEach(item -> notifyProgress(progressListener, item));
                    termination = group.timeout() ? "step_timeout" : "step_failed";
                    nextStepIndex = group.nextStepIndex();
                    break;
                }
                group.iterations().forEach(item -> notifyProgress(progressListener, item));
                nextStepIndex = groupEnd;
                index = groupEnd;
                continue;
            }

            String step = cleanAction(safeSteps.get(index));
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
                        ? defaultObservation(context, step, index)
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
            index++;
        }
        if ("completed".equals(termination) && nextStepIndex < safeSteps.size()) {
            termination = "step_budget";
        }
        if ("completed".equals(termination) && safeSteps.isEmpty()) {
            termination = "empty_plan";
        }
        return new LoopResult(List.copyOf(iterations), termination, nextStepIndex);
    }

    private List<Integer> mergeCompletedStepIndices(
        List<LoopIteration> recoveredIterations,
        List<Integer> persistedCompletedStepIndices
    ) {
        List<Integer> fromIterations = normalizeCompletedStepIndices(
            recoveredIterations, Integer.MAX_VALUE
        );
        if (persistedCompletedStepIndices == null || persistedCompletedStepIndices.isEmpty()) {
            return fromIterations;
        }
        return java.util.stream.Stream.concat(
                fromIterations.stream(), persistedCompletedStepIndices.stream()
            )
            .filter(index -> index != null && index >= 0)
            .distinct()
            .sorted()
            .toList();
    }

    private List<Integer> sanitizeCompletedStepIndices(
        List<Integer> indices, int stepCount
    ) {
        if (indices == null || indices.isEmpty()) {
            return List.of();
        }
        return indices.stream()
            .filter(index -> index != null && index >= 0 && index < stepCount)
            .distinct()
            .sorted()
            .toList();
    }

    private List<Integer> normalizeCompletedStepIndices(
        List<LoopIteration> recoveredIterations, int stepCount
    ) {
        if (recoveredIterations == null || recoveredIterations.isEmpty()) {
            return List.of();
        }
        return recoveredIterations.stream()
            .filter(iteration -> iteration != null && "completed".equals(iteration.status()))
            .map(LoopIteration::iteration)
            .filter(iteration -> iteration >= 1 && iteration <= stepCount)
            .map(iteration -> iteration - 1)
            .distinct()
            .sorted()
            .toList();
    }

    private int parallelGroupEnd(
        List<String> steps, int start, int last, int maxParallelSteps
    ) {
        if (maxParallelSteps <= 1 || !isParallelStep(steps.get(start))) {
            return start + 1;
        }
        int end = start;
        while (end < last && end - start < maxParallelSteps && isParallelStep(steps.get(end))) {
            end++;
        }
        return end;
    }

    private boolean isParallelStep(String action) {
        return action != null && action.trim().startsWith("[parallel]");
    }

    private String cleanAction(String action) {
        if (!isParallelStep(action)) {
            return action;
        }
        return action.trim().substring("[parallel]".length()).trim();
    }

    private ParallelGroupResult executeParallelGroup(
        AgentContextService.AgentContext context,
        List<String> steps,
        int start,
        int end,
        ExecutionPolicy policy,
        long loopStartedNanos,
        StepExecutor executor,
        List<Integer> recoveredCompletedSteps
    ) {
        List<Callable<StepOutcome>> tasks = new ArrayList<>();
        List<Integer> taskIndexes = new ArrayList<>();
        for (int index = start; index < end; index++) {
            if (recoveredCompletedSteps.contains(index)) {
                continue;
            }
            final int stepIndex = index;
            taskIndexes.add(stepIndex);
            tasks.add(() -> executeStepWithRetry(
                context, cleanAction(steps.get(stepIndex)), stepIndex, policy, loopStartedNanos, executor
            ));
        }

        List<Future<StepOutcome>> futures = new ArrayList<>();
        for (Callable<StepOutcome> task : tasks) {
            futures.add(PARALLEL_EXECUTOR.submit(task));
        }
        long remainingMillis = policy.remainingMillis(loopStartedNanos);
        long deadlineNanos = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(Math.max(1, remainingMillis));
        List<StepOutcome> outcomes = new ArrayList<>();
        for (int offset = 0; offset < futures.size(); offset++) {
            Future<StepOutcome> future = futures.get(offset);
            int stepIndex = taskIndexes.get(offset);
            try {
                long waitMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(
                    Math.max(1, deadlineNanos - System.nanoTime())
                ));
                outcomes.add(future.get(waitMillis, TimeUnit.MILLISECONDS));
            } catch (TimeoutException | CancellationException error) {
                future.cancel(true);
                outcomes.add(StepOutcome.failure(
                    stepIndex, cleanAction(steps.get(stepIndex)), 1, true, error
                ));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                outcomes.add(StepOutcome.failure(
                    stepIndex, cleanAction(steps.get(stepIndex)), 1, true, error
                ));
            } catch (ExecutionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                outcomes.add(StepOutcome.failure(
                    stepIndex, cleanAction(steps.get(stepIndex)), 1,
                    cause instanceof LoopStepTimeoutException, cause
                ));
            }
        }
        futures.forEach(future -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });

        List<LoopIteration> iterations = outcomes.stream()
            .sorted(java.util.Comparator.comparingInt(StepOutcome::index))
            .map(StepOutcome::iteration)
            .toList();
        int next = outcomes.stream()
            .filter(outcome -> !outcome.success())
            .mapToInt(StepOutcome::index)
            .min()
            .orElse(end);
        boolean failed = outcomes.stream().anyMatch(outcome -> !outcome.success());
        boolean timeout = outcomes.stream().anyMatch(StepOutcome::timeout);
        return new ParallelGroupResult(iterations, failed, timeout, next);
    }

    private StepOutcome executeStepWithRetry(
        AgentContextService.AgentContext context,
        String action,
        int index,
        ExecutionPolicy policy,
        long loopStartedNanos,
        StepExecutor executor
    ) {
        int attempts = 0;
        Throwable lastError = null;
        long stepStartedNanos = System.nanoTime();
        while (attempts < policy.maxStepRetries() + 1) {
            attempts++;
            try {
                long remaining = policy.remainingMillis(loopStartedNanos);
                if (remaining <= 0) {
                    throw new LoopStepTimeoutException();
                }
                String observation = executeWithTimeout(
                    executor, context, action, index,
                    Math.min(policy.stepTimeoutMillis(), remaining)
                );
                if (observation == null || observation.isBlank()) {
                    throw new IllegalStateException("empty_observation");
                }
                return StepOutcome.success(index, action, observation, attempts, elapsedMillis(stepStartedNanos));
            } catch (Throwable error) {
                lastError = error;
                if (policy.remainingMillis(loopStartedNanos) <= 0) {
                    break;
                }
            }
        }
        boolean timeout = lastError instanceof LoopStepTimeoutException;
        return StepOutcome.failure(index, action, attempts, timeout, lastError, elapsedMillis(stepStartedNanos));
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
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
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
        return defaultObservation(context, "", index);
    }

    public static String defaultObservation(
        AgentContextService.AgentContext context, String action, int index
    ) {
        return switch (index) {
            case 0 -> "已读取面试报告；识别到 " + context.weaknesses().size() + " 个待关注项。";
            case 1 -> action.contains("核对")
                ? context.sources().isEmpty()
                    ? "Skill/RAG 未提供可引用来源，本轮回答不得声称使用了外部依据。"
                    : "已核对 Skill/RAG 来源，共 " + context.sources().size() + " 个可引用来源。"
                : action.contains("确认当前目标")
                    ? "已确认当前目标和资料边界。"
                    : "已整理固定资料边界，未声称使用不存在的外部依据。";
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
        long totalTimeoutMillis,
        int maxParallelSteps
    ) {
        private static final long DEFAULT_STEP_TIMEOUT_MILLIS = 5_000;
        private static final long DEFAULT_TOTAL_TIMEOUT_MILLIS = 15_000;

        public ExecutionPolicy {
            maxSteps = Math.max(1, maxSteps);
            maxStepRetries = Math.max(0, maxStepRetries);
            stepTimeoutMillis = Math.max(1, stepTimeoutMillis);
            totalTimeoutMillis = Math.max(stepTimeoutMillis, totalTimeoutMillis);
            maxParallelSteps = Math.max(1, maxParallelSteps);
        }

        public ExecutionPolicy(
            int maxSteps, int maxStepRetries, long stepTimeoutMillis, long totalTimeoutMillis
        ) {
            this(maxSteps, maxStepRetries, stepTimeoutMillis, totalTimeoutMillis, 1);
        }

        public static ExecutionPolicy defaults(int maxSteps) {
            return new ExecutionPolicy(
                maxSteps, 0, DEFAULT_STEP_TIMEOUT_MILLIS, DEFAULT_TOTAL_TIMEOUT_MILLIS, 1
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

    private static final ExecutorService PARALLEL_EXECUTOR = Executors.newFixedThreadPool(
        3,
        runnable -> {
            Thread thread = new Thread(runnable, "agent-loop-parallel-step");
            thread.setDaemon(true);
            return thread;
        }
    );

    private record ParallelGroupResult(
        List<LoopIteration> iterations,
        boolean failed,
        boolean timeout,
        int nextStepIndex
    ) {
    }

    private record StepOutcome(
        int index,
        LoopIteration iteration,
        boolean success,
        boolean timeout
    ) {
        static StepOutcome success(int index, String action, String observation, int attempts, long duration) {
            return new StepOutcome(index,
                new LoopIteration(index + 1, action, observation, "completed", attempts, duration, null),
                true, false);
        }

        static StepOutcome failure(int index, String action, int attempts, boolean timeout, Throwable error) {
            return failure(index, action, attempts, timeout, error, 0);
        }

        static StepOutcome failure(
            int index, String action, int attempts, boolean timeout, Throwable error, long duration
        ) {
            String errorName = error == null ? "unknown" : error.getClass().getSimpleName();
            String status = timeout ? "timeout" : "failed";
            String reason = error == null ? "unknown" : errorName + ": " + error.getMessage();
            return new StepOutcome(index,
                new LoopIteration(index + 1, action,
                    "步骤执行失败（" + errorName + "）", status, attempts, duration, reason),
                false, timeout);
        }
    }

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
