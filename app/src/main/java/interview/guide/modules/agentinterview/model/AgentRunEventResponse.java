package interview.guide.modules.agentinterview.model;

import interview.guide.common.agent.runtime.AgentRunStatus;
import interview.guide.infrastructure.agent.persistence.AgentStepEntity;

import java.time.LocalDateTime;

public record AgentRunEventResponse(
    String runId,
    Long stepSequence,
    String eventType,
    AgentRunStatus previousStatus,
    AgentRunStatus status,
    LocalDateTime occurredAt
) {

  public static AgentRunEventResponse from(AgentStepEntity step) {
    return new AgentRunEventResponse(
        step.getRunId(),
        step.getStepSequence(),
        switch (step.getStatus()) {
          case PAUSED -> "run.paused";
          case CANCELLED -> "run.cancelled";
          default -> "run.status_changed";
        },
        step.getPreviousStatus(),
        step.getStatus(),
        step.getCreatedAt()
    );
  }
}
