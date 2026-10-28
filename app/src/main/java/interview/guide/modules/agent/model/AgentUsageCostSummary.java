package interview.guide.modules.agent.model;

import java.math.BigDecimal;

/** 按 Provider 和币种聚合的 Agent 费用。 */
public record AgentUsageCostSummary(
    String provider,
    String currency,
    BigDecimal amount
) {}
