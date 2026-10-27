package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/** Agent 动作在前端展示的安全摘要。 */
public record AgentActionResponse(
    String actionId,
    String interviewSessionId,
    String agentSessionId,
    String toolId,
    String riskLevel,
    AgentActionStatus status,
    String title,
    String previewText,
    String draftContent,
    String resultSummary,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime confirmedAt,
    LocalDateTime completedAt
) {
    public static AgentActionResponse from(AgentActionRequestEntity entity) {
        return new AgentActionResponse(
            entity.getActionId(),
            entity.getInterviewSessionId(),
            entity.getAgentSessionId(),
            entity.getToolId(),
            entity.getRiskLevel(),
            entity.getStatus(),
            entity.getTitle(),
            entity.getPreviewText(),
            entity.getDraftContent(),
            entity.getResultSummary(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getConfirmedAt(),
            entity.getCompletedAt()
        );
    }
}
