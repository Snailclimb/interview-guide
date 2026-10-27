package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/** Agent 训练任务的用户可见摘要。 */
public record AgentTrainingTaskResponse(
    String taskId,
    String sourceInterviewSessionId,
    String sourceAgentSessionId,
    String sourceActionId,
    String title,
    String description,
    AgentTrainingTaskStatus status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime completedAt
) {
    public static AgentTrainingTaskResponse from(AgentTrainingTaskEntity entity) {
        return new AgentTrainingTaskResponse(
            entity.getTaskId(),
            entity.getSourceInterviewSessionId(),
            entity.getSourceAgentSessionId(),
            entity.getSourceActionId(),
            entity.getTitle(),
            entity.getDescription(),
            entity.getStatus(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getCompletedAt()
        );
    }
}
