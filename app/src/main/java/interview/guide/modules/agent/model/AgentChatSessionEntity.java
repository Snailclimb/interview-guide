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
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 面试教练 Agent 的持久化会话。
 *
 * <p>Agent 会话独立于原有面试会话，避免改动既有面试数据结构；通过
 * {@code interviewSessionId} 关联到一场面试的只读上下文。</p>
 */
@Entity
@Table(name = "agent_chat_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentChatSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interview_session_id", nullable = false, length = 36)
    private String interviewSessionId;

    @Column(name = "agent_session_id", nullable = false, unique = true, length = 36)
    private String agentSessionId;

    @Column(length = 120)
    private String title;

    @Column(length = 32)
    private String goal;

    @Column(name = "provider_id", length = 64)
    private String providerId;

    @Column(name = "memory_key", length = 64)
    private String memoryKey;

    /** Agent 会话归属的正式用户 ID；迁移期仍兼容旧浏览器身份键。 */
    @Column(name = "owner_key", length = 64)
    private String ownerKey;

    @Column(nullable = false)
    @Builder.Default
    private boolean archived = false;

    @Column(name = "run_id", length = 36)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_status", nullable = false, length = 16)
    @Builder.Default
    private AgentRunStatus runStatus = AgentRunStatus.IDLE;

    @Column(name = "active_message_id")
    private Long activeMessageId;

    @Column(name = "run_started_at")
    private LocalDateTime runStartedAt;

    @Column(name = "run_finished_at")
    private LocalDateTime runFinishedAt;

    @Column(name = "run_error", length = 500)
    private String runError;

    @Column(name = "run_step", length = 32)
    private String runStep;

    @Column(name = "run_step_status", length = 16)
    private String runStepStatus;

    @Column(name = "run_notice", length = 500)
    private String runNotice;

    @Column(name = "run_sources", columnDefinition = "TEXT")
    private String runSourcesJson;

    @Column(name = "loop_plan", columnDefinition = "TEXT")
    private String loopPlan;

    @Column(name = "loop_next_step", nullable = false)
    @Builder.Default
    private int loopNextStep = 0;

    @Column(name = "run_prompt_tokens")
    private Integer runPromptTokens;

    @Column(name = "run_completion_tokens")
    private Integer runCompletionTokens;

    @Column(name = "run_total_tokens")
    private Integer runTotalTokens;

    @Column(name = "run_model_calls")
    private Integer runModelCalls;

    @Column(name = "run_duration_millis")
    private Long runDurationMillis;

    @Column(name = "run_cost", precision = 20, scale = 10)
    private BigDecimal runCost;

    @Column(name = "run_currency", length = 8)
    private String runCurrency;

    @Column(name = "run_provider", length = 64)
    private String runProvider;

    @Column(name = "run_model", length = 128)
    private String runModel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
