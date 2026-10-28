package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentConversationCreationRequestEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Agent 对话创建请求的数据库级幂等存储。 */
@Repository
public interface AgentConversationCreationRequestRepository
    extends JpaRepository<AgentConversationCreationRequestEntity, Long> {

    Optional<AgentConversationCreationRequestEntity> findByOwnerKeyAndIdempotencyKey(
        String ownerKey, String idempotencyKey
    );

    /**
     * 先占用请求键再创建线程；PostgreSQL 的唯一约束会等待并发事务完成，
     * 因此只有首个请求返回 1，后续请求返回 0 并复用首个请求记录。
     */
    @Modifying
    @Query(value = """
        INSERT INTO agent_conversation_creation_requests
            (owner_key, interview_session_id, idempotency_key, agent_session_id)
        VALUES (:ownerKey, :interviewSessionId, :idempotencyKey, :agentSessionId)
        ON CONFLICT (owner_key, idempotency_key) DO NOTHING
        """, nativeQuery = true)
    int tryClaim(
        @Param("ownerKey") String ownerKey,
        @Param("interviewSessionId") String interviewSessionId,
        @Param("idempotencyKey") String idempotencyKey,
        @Param("agentSessionId") String agentSessionId
    );
}
