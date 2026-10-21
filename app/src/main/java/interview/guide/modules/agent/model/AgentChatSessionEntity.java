package interview.guide.modules.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @Column(name = "interview_session_id", nullable = false, unique = true, length = 36)
    private String interviewSessionId;

    @Column(length = 32)
    private String goal;

    @Column(name = "provider_id", length = 64)
    private String providerId;

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
