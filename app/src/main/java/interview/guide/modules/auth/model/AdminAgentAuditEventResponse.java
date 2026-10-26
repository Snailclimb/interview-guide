package interview.guide.modules.auth.model;

import interview.guide.modules.agent.model.AgentAuditEventEntity;
import java.time.LocalDateTime;

/** 管理员查看的 Agent 低敏审计；不返回 Prompt、回答正文或工具参数。 */
public record AdminAgentAuditEventResponse(
    Long id,
    String ownerKey,
    String interviewSessionId,
    String agentSessionId,
    String action,
    String resourceType,
    String resourceId,
    String result,
    LocalDateTime createdAt
) {
    public static AdminAgentAuditEventResponse from(AgentAuditEventEntity event) {
        return new AdminAgentAuditEventResponse(
            event.getId(), event.getOwnerKey(), event.getInterviewSessionId(),
            event.getAgentSessionId(), event.getAction(), event.getResourceType(),
            event.getResourceId(), event.getResult(), event.getCreatedAt()
        );
    }
}
