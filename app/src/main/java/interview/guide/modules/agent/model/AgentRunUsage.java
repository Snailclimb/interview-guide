package interview.guide.modules.agent.model;

import java.math.BigDecimal;

/**
 * 最近一次 Agent 运行的可展示用量摘要。
 *
 * <p>这里只保存聚合后的 Token、耗时和费用，不保存提示词、回答或会话内容；费用未配置时为
 * {@code null}，避免用未知价格伪造金额。</p>
 */
public record AgentRunUsage(
    int promptTokens,
    int completionTokens,
    int totalTokens,
    int modelCalls,
    long durationMillis,
    BigDecimal cost,
    String currency,
    String provider,
    String model
) {
    public AgentRunUsage {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        totalTokens = Math.max(0, totalTokens);
        modelCalls = Math.max(0, modelCalls);
        durationMillis = Math.max(0, durationMillis);
        currency = currency == null || currency.isBlank() ? null : currency;
        provider = provider == null || provider.isBlank() ? null : provider;
        model = model == null || model.isBlank() ? null : model;
        if (cost != null && cost.signum() < 0) {
            cost = BigDecimal.ZERO;
        }
    }

    public static AgentRunUsage empty() {
        return new AgentRunUsage(0, 0, 0, 0, 0, null, null, null, null);
    }

    public boolean available() {
        return modelCalls > 0 || totalTokens > 0 || durationMillis > 0;
    }
}
