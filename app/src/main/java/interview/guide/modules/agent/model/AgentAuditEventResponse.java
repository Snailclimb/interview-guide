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
    LocalDateTime createdAt,
    String interviewTitle,
    String agentSessionTitle
) {
    /** 兼容未携带来源名称的旧调用方。 */
    public AgentAuditEventResponse(
        String interviewSessionId,
        String agentSessionId,
        String action,
        String resourceType,
        String resourceId,
        String result,
        LocalDateTime createdAt
    ) {
        this(interviewSessionId, agentSessionId, action, resourceType, resourceId, result, createdAt, null, null);
    }

    public static AgentAuditEventResponse from(AgentAuditEventEntity event) {
        return new AgentAuditEventResponse(
            event.getInterviewSessionId(),
            event.getAgentSessionId(),
            event.getAction(),
            event.getResourceType(),
            event.getResourceId(),
            event.getResult(),
            event.getCreatedAt(),
            null,
            null
        );
    }

    public AgentAuditEventResponse withSourceNames(String interviewTitle, String agentSessionTitle) {
        return new AgentAuditEventResponse(
            interviewSessionId, agentSessionId, action, resourceType, resourceId, result,
            createdAt, interviewTitle, agentSessionTitle
        );
    }
}
