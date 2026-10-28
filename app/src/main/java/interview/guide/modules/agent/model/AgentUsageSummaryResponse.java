package interview.guide.modules.agent.model;

import java.math.BigDecimal;
import java.util.List;

/** 当前登录用户跨 Agent 对话的累计用量。 */
public record AgentUsageSummaryResponse(
    long runCount,
    long completedRunCount,
    long failedRunCount,
    long cancelledRunCount,
    long promptTokens,
    long completionTokens,
    long totalTokens,
    long modelCalls,
    long durationMillis,
    List<AgentUsageCostSummary> costs
) {
    public AgentUsageSummaryResponse {
        costs = costs == null ? List.of() : List.copyOf(costs);
    }

    public static AgentUsageSummaryResponse empty() {
        return new AgentUsageSummaryResponse(
            0, 0, 0, 0, 0, 0, 0, 0, 0, List.of()
        );
    }
}
