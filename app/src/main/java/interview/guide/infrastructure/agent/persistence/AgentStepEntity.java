package interview.guide.infrastructure.agent.persistence;

import interview.guide.common.agent.runtime.AgentRunStatus;
import interview.guide.common.agent.runtime.AgentStepType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Immutable
@Table(
    name = "agent_steps",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_steps_run_sequence",
        columnNames = {"run_id", "step_sequence"}
    )
)
public class AgentStepEntity {

  @Id
  @Column(name = "step_id", nullable = false, updatable = false, length = 36)
  private String stepId;

  @Column(name = "run_id", nullable = false, updatable = false, length = 36)
  private String runId;

  @Column(name = "step_sequence", nullable = false, updatable = false)
  private Long stepSequence;

  @Enumerated(EnumType.STRING)
  @Column(name = "step_type", nullable = false, updatable = false, length = 40)
  private AgentStepType stepType;

  @Enumerated(EnumType.STRING)
  @Column(name = "previous_status", nullable = false, updatable = false, length = 24)
  private AgentRunStatus previousStatus;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, length = 24)
  private AgentRunStatus status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected AgentStepEntity() {
  }

  public static AgentStepEntity statusChanged(
      String runId,
      long stepSequence,
      AgentRunStatus previousStatus,
      AgentRunStatus status) {
    AgentStepEntity entity = new AgentStepEntity();
    entity.stepId = UUID.randomUUID().toString();
    entity.runId = runId;
    entity.stepSequence = stepSequence;
    entity.stepType = AgentStepType.RUN_STATUS_CHANGED;
    entity.previousStatus = previousStatus;
    entity.status = status;
    entity.createdAt = LocalDateTime.now();
    return entity;
  }

  public String getStepId() {
    return stepId;
  }

  public String getRunId() {
    return runId;
  }

  public Long getStepSequence() {
    return stepSequence;
  }

  public AgentStepType getStepType() {
    return stepType;
  }

  public AgentRunStatus getPreviousStatus() {
    return previousStatus;
  }

  public AgentRunStatus getStatus() {
    return status;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
