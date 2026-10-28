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
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Agent 对话创建请求的幂等占位记录。
 *
 * <p>先用数据库唯一键占住请求，再创建实际线程；网络超时重试或多实例并发时，
 * 后续请求会复用同一个 agentSessionId，不会重复生成线程。</p>
 */
@Entity
@Table(name = "agent_conversation_creation_requests")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentConversationCreationRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_key", nullable = false, length = 64)
    private String ownerKey;

    @Column(name = "interview_session_id", nullable = false, length = 36)
    private String interviewSessionId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "agent_session_id", nullable = false, unique = true, length = 36)
    private String agentSessionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
