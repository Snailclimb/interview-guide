package interview.guide.modules.agent.model;

/**
 * 一条可恢复的 Agent Loop 轨迹。
 *
 * <p>只保存动作、状态和受控工具返回的观察摘要，不保存模型隐藏推理或原始上下文。</p>
 */
public record AgentLoopTraceItem(
    int iteration,
    String action,
    String status,
    String observation,
    int attempts,
    long durationMillis,
    String failureReason
) {
    public AgentLoopTraceItem {
        iteration = Math.max(1, iteration);
        action = truncate(action, 256);
        status = status == null || status.isBlank() ? "unknown" : status;
        observation = truncate(observation, 2_000);
        attempts = Math.max(0, attempts);
        durationMillis = Math.max(0, durationMillis);
        failureReason = failureReason == null ? null : truncate(failureReason, 256);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }
}
