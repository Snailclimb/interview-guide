package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * RAG聊天消息Repository
 */
@Repository
public interface RagChatMessageRepository extends JpaRepository<RagChatMessageEntity, Long> {

    /**
     * 获取会话的所有消息（按顺序）
     */
    List<RagChatMessageEntity> findBySessionIdOrderByMessageOrderAsc(Long sessionId);

    /**
     * 获取会话的最后一条消息
     */
    Optional<RagChatMessageEntity> findTopBySessionIdOrderByMessageOrderDesc(Long sessionId);

    /**
     * 获取会话中最近 N 条已完成的消息（按 messageOrder 倒序取，结果需要反转为正序）
     */
    @Query("SELECT m FROM RagChatMessageEntity m WHERE m.session.id = :sessionId AND m.completed = true ORDER BY m.messageOrder DESC")
    List<RagChatMessageEntity> findRecentCompletedBySessionId(@Param("sessionId") Long sessionId, Pageable pageable);

    /**
    @Query("SELECT COUNT(m) FROM RagChatMessageEntity m WHERE m.session.id = :sessionId")
    Integer countBySessionId(@Param("sessionId") Long sessionId);

    /**
     * 查找未完成的消息（流式响应中断时清理用）
     */
    List<RagChatMessageEntity> findBySessionIdAndCompletedFalse(Long sessionId);

    /**
     * 删除会话的所有消息
     */
    void deleteBySessionId(Long sessionId);

    /**
     * 统计所有用户消息数（即总提问次数）
     */
    long countByType(MessageType type);
}
