package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 聊天会话实体
 * 一个会话可以关联多个知识库，包含多条消息
 */
@Entity
@Table(name = "rag_chat_sessions", indexes = {
    @Index(name = "idx_rag_session_updated", columnList = "updatedAt")
})
@Getter
@Setter
@NoArgsConstructor
public class RagChatSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 会话标题（可自动生成或用户自定义）
     */
    @Column(nullable = false)
    private String title;

    /** NULL 表示历史共享 RAG 会话。 */
    @Column(name = "owner_id", length = 64)
    private String ownerId;

    /**
     * 会话状态
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SessionStatus status = SessionStatus.ACTIVE;

    /**
     * 多对多：会话关联的知识库
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "rag_session_knowledge_bases",
        joinColumns = @JoinColumn(name = "session_id"),
        inverseJoinColumns = @JoinColumn(name = "knowledge_base_id")
    )
    private Set<KnowledgeBaseEntity> knowledgeBases = new HashSet<>();

    /**
     * 一对多：会话的消息列表
     */
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("messageOrder ASC")
    private List<RagChatMessageEntity> messages = new ArrayList<>();

    /**
     * 创建时间
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间（最后一次消息时间）
     */
    private LocalDateTime updatedAt;

    /**
     * 消息数量（冗余字段，方便查询）
     */
    private Integer messageCount = 0;

    /**
     * 是否置顶
     */
    @Column(columnDefinition = "boolean default false")
    private Boolean isPinned = false;

    public enum SessionStatus {
        ACTIVE,    // 活跃会话
        ARCHIVED   // 已归档
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @PostLoad
    protected void onLoad() {
        // 确保 isPinned 字段始终有值（兼容旧数据）
        if (isPinned == null) {
            isPinned = false;
        }
    }

    /**
     * 便捷方法：添加消息
     */
    public void addMessage(RagChatMessageEntity message) {
        messages.add(message);
        message.setSession(this);
        messageCount = messages.size();
        updatedAt = LocalDateTime.now();
    }

    /**
     * 便捷方法：获取知识库ID列表
     */
    public List<Long> getKnowledgeBaseIds() {
        return knowledgeBases.stream()
            .map(KnowledgeBaseEntity::getId)
            .toList();
    }
}
