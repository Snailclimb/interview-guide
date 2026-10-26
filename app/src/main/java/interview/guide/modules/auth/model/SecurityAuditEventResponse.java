package interview.guide.modules.auth.model;

import java.time.LocalDateTime;

public record SecurityAuditEventResponse(
    Long id,
    String actorUserId,
    String targetUserId,
    String action,
    String resourceType,
    String resourceId,
    String result,
    LocalDateTime createdAt
) {
    public static SecurityAuditEventResponse from(SecurityAuditEventEntity event) {
        return new SecurityAuditEventResponse(
            event.getId(), event.getActorUserId(), event.getTargetUserId(), event.getAction(),
            event.getResourceType(), event.getResourceId(), event.getResult(), event.getCreatedAt()
        );
    }
}
