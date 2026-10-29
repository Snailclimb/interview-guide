package interview.guide.modules.agent.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 单次 Agent 运行的用量明细，补充可定位的面试和 Agent 会话名称。 */
public record AgentUsageRunResponse(
    String runId,
    String interviewSessionId,
    String interviewTitle,
    String skillId,
    String agentSessionId,
    String agentSessionTitle,
    String runStatus,
    int promptTokens,
    int completionTokens,
    int totalTokens,
    int modelCalls,
    long durationMillis,
    BigDecimal cost,
    String currency,
    String provider,
    String model,
    LocalDateTime runStartedAt,
    LocalDateTime runFinishedAt,
    LocalDateTime createdAt
) {
}
