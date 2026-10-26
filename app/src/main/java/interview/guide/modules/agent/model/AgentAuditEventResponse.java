package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/** Agent 低敏审计查询结果；不返回用户问题、回答正文或 API 密钥。 */
public record AgentAuditEventResponse(
    String interviewSessionId,
    String agentSessionId,
    String action,
    String resourceType,
    String resourceId,
    String result,
    LocalDateTime createdAt
) {
    public static AgentAuditEventResponse from(AgentAuditEventEntity event) {
        return new AgentAuditEventResponse(
            event.getInterviewSessionId(),
            event.getAgentSessionId(),
            event.getAction(),
            event.getResourceType(),
            event.getResourceId(),
            event.getResult(),
            event.getCreatedAt()
        );
    }
}
