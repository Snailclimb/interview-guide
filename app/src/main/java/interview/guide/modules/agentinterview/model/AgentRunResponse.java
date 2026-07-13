package interview.guide.modules.agentinterview.model;

import interview.guide.common.agent.runtime.AgentRunStatus;
import interview.guide.common.agent.runtime.AgentType;
import interview.guide.infrastructure.agent.persistence.AgentRunEntity;

import java.time.LocalDateTime;

public record AgentRunResponse(
    String runId,
    AgentType agentType,
    String businessSessionId,
    AgentRunStatus status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

  public static AgentRunResponse from(AgentRunEntity entity) {
    return new AgentRunResponse(
        entity.getRunId(),
        entity.getAgentType(),
        entity.getBusinessSessionId(),
        entity.getStatus(),
        entity.getCreatedAt(),
        entity.getUpdatedAt()
    );
  }
}
