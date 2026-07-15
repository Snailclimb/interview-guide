package interview.guide.infrastructure.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "agent_checkpoints",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_checkpoints_run",
        columnNames = "run_id"
    )
)
public class AgentCheckpointEntity {

  @Id
  @Column(name = "checkpoint_id", nullable = false, updatable = false, length = 36)
  private String checkpointId;

  @Column(name = "run_id", nullable = false, updatable = false, length = 36)
  private String runId;

  @Column(name = "last_applied_step_sequence", nullable = false)
  private Long lastAppliedStepSequence;

  @Column(name = "recovery_state", nullable = false, columnDefinition = "TEXT")
  private String recoveryState;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  protected AgentCheckpointEntity() {
  }

  public static AgentCheckpointEntity create(
      String runId,
      long lastAppliedStepSequence,
      String recoveryState) {
    AgentCheckpointEntity entity = new AgentCheckpointEntity();
    entity.checkpointId = UUID.randomUUID().toString();
    entity.runId = runId;
    entity.lastAppliedStepSequence = lastAppliedStepSequence;
    entity.recoveryState = recoveryState;
    return entity;
  }

  public void replace(long lastAppliedStepSequence, String recoveryState) {
    this.lastAppliedStepSequence = lastAppliedStepSequence;
    this.recoveryState = recoveryState;
  }

  @PrePersist
  void onCreate() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = LocalDateTime.now();
  }

  public String getCheckpointId() {
    return checkpointId;
  }

  public String getRunId() {
    return runId;
  }

  public Long getLastAppliedStepSequence() {
    return lastAppliedStepSequence;
  }

  public String getRecoveryState() {
    return recoveryState;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
