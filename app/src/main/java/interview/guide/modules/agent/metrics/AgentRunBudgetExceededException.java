package interview.guide.modules.agent.metrics;

/**
 * Agent 单次运行达到模型调用预算时抛出的可识别异常。
 *
 * <p>这是运行边界而不是 Provider 故障，不应继续触发重试或切换 Provider。</p>
 */
public class AgentRunBudgetExceededException extends RuntimeException {
    public AgentRunBudgetExceededException(int limit) {
        super("Agent 单次运行已达到模型调用预算（" + limit + " 次）");
    }
}
