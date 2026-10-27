package interview.guide.modules.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 动作请求和草稿。
 *
 * <p>它是独立的动作资源，不等同于简历、面试报告或知识库实体。第一批动作确认后
 * 只把草稿标记为已保存，避免模型或重试直接覆盖用户原始数据。</p>
 */
@Entity
@Table(name = "agent_action_requests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentActionRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_id", nullable = false, unique = true, length = 64)
    private String actionId;

    @Column(name = "owner_key", nullable = false, length = 64)
    private String ownerKey;

    @Column(name = "interview_session_id", nullable = false, length = 64)
    private String interviewSessionId;

    @Column(name = "agent_session_id", nullable = false, length = 64)
    private String agentSessionId;

    @Column(name = "tool_id", nullable = false, length = 64)
    private String toolId;

    @Column(name = "risk_level", nullable = false, length = 32)
    private String riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AgentActionStatus status;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(name = "preview_text", nullable = false, columnDefinition = "TEXT")
    private String previewText;

    @Column(name = "draft_content", nullable = false, columnDefinition = "TEXT")
    private String draftContent;

    /** 记忆动作使用的结构化分类；其他动作为空。 */
    @Column(name = "memory_category", length = 32)
    private String memoryCategory;

    /** 记忆动作的适用方向；其他动作为空。 */
    @Column(name = "memory_job_scope", length = 64)
    private String memoryJobScope;

    /** 改名动作的目标名称；其他动作为空。 */
    @Column(name = "target_title", length = 120)
    private String targetTitle;

    @Column(name = "result_summary", length = 500)
    private String resultSummary;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 当前动作已实际调用业务执行器的次数，用于限制瞬时故障重试。 */
    @Column(name = "execution_attempts", nullable = false)
    private Integer executionAttempts;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (executionAttempts == null) {
            executionAttempts = 0;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
