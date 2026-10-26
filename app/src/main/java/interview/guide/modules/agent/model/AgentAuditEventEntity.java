package interview.guide.modules.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Agent 低敏操作审计事件；不保存 Prompt、回答正文或模型密钥。 */
@Entity
@Table(name = "agent_audit_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentAuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_key", length = 64)
    private String ownerKey;

    @Column(name = "interview_session_id", length = 64)
    private String interviewSessionId;

    @Column(name = "agent_session_id", length = 64)
    private String agentSessionId;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 32)
    private String resourceType;

    @Column(name = "resource_id", length = 128)
    private String resourceId;

    @Column(length = 32)
    private String result;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
