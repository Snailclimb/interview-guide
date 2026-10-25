package interview.guide.modules.agent.metrics;

/**
 * Agent 单次运行达到模型调用预算时抛出的可识别异常。
 *
 * <p>这是运行边界而不是 Provider 故障，不应继续触发重试或切换 Provider。</p>
 */
public class AgentRunBudgetExceededException extends RuntimeException {
    public AgentRunBudgetExceededException(int limit) {
        this("模型调用", limit);
    }

    public AgentRunBudgetExceededException(String resource, int limit) {
        super("Agent 单次运行已达到" + resource + "预算（" + limit + " 次）");
    }
}
