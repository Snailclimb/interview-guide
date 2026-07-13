package interview.guide.infrastructure.agent.persistence;

import interview.guide.common.agent.runtime.AgentRunStatus;
import interview.guide.common.agent.runtime.AgentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "agent_runs",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_run_idempotency_key",
        columnNames = "idempotency_key"
    )
)
public class AgentRunEntity {

  @Id
  @Column(name = "run_id", nullable = false, length = 36)
  private String runId;

  @Enumerated(EnumType.STRING)
  @Column(name = "agent_type", nullable = false, length = 40)
  private AgentType agentType;

  @Column(name = "business_session_id", nullable = false, length = 64)
  private String businessSessionId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private AgentRunStatus status;

  @Column(name = "idempotency_key", nullable = false, length = 128)
  private String idempotencyKey;

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Version
  private Long version;

  protected AgentRunEntity() {
  }

  public static AgentRunEntity create(
      AgentType agentType,
      String businessSessionId,
      String idempotencyKey,
      String requestFingerprint) {
    AgentRunEntity entity = new AgentRunEntity();
    entity.runId = UUID.randomUUID().toString();
    entity.agentType = agentType;
    entity.businessSessionId = businessSessionId;
    entity.status = AgentRunStatus.CREATED;
    entity.idempotencyKey = idempotencyKey;
    entity.requestFingerprint = requestFingerprint;
    return entity;
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

  public boolean pause() {
    if (status != AgentRunStatus.CREATED && status != AgentRunStatus.RUNNING) {
      return false;
    }
    status = AgentRunStatus.PAUSED;
    return true;
  }

  public boolean cancel() {
    if (status == AgentRunStatus.COMPLETED
        || status == AgentRunStatus.FAILED
        || status == AgentRunStatus.CANCELLED) {
      return false;
    }
    status = AgentRunStatus.CANCELLED;
    return true;
  }

  public String getRunId() {
    return runId;
  }

  public AgentType getAgentType() {
    return agentType;
  }

  public String getBusinessSessionId() {
    return businessSessionId;
  }

  public AgentRunStatus getStatus() {
    return status;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public Long getVersion() {
    return version;
  }
}
