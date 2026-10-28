package interview.guide.modules.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次 Agent 运行的不可变用量快照。
 *
 * <p>会话表中的 run_* 字段只代表最近一次运行；本表按 runId 保存每次终态，
 * 用于跨 Agent 对话累计 Token、耗时和费用。</p>
 */
@Entity
@Table(name = "agent_run_usages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_key", length = 64)
    private String ownerKey;

    @Column(name = "interview_session_id", nullable = false, length = 36)
    private String interviewSessionId;

    @Column(name = "agent_session_id", nullable = false, length = 36)
    private String agentSessionId;

    @Column(name = "run_id", nullable = false, unique = true, length = 36)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_status", nullable = false, length = 16)
    private AgentRunStatus runStatus;

    @Column(name = "run_started_at")
    private LocalDateTime runStartedAt;

    @Column(name = "run_finished_at")
    private LocalDateTime runFinishedAt;

    @Column(name = "prompt_tokens", nullable = false)
    @Builder.Default
    private int promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    @Builder.Default
    private int completionTokens = 0;

    @Column(name = "total_tokens", nullable = false)
    @Builder.Default
    private int totalTokens = 0;

    @Column(name = "model_calls", nullable = false)
    @Builder.Default
    private int modelCalls = 0;

    @Column(name = "duration_millis", nullable = false)
    @Builder.Default
    private long durationMillis = 0;

    @Column(name = "cost", precision = 20, scale = 10)
    private BigDecimal cost;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "provider", length = 64)
    private String provider;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
