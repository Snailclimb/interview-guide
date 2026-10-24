package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentChatSessionEntity;
import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;
import interview.guide.modules.agent.model.AgentRunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentChatSessionRepository extends JpaRepository<AgentChatSessionEntity, Long> {

    Optional<AgentChatSessionEntity> findByInterviewSessionId(String interviewSessionId);

    /** 未指定线程时沿用最早创建的默认线程，兼容旧链接。 */
    Optional<AgentChatSessionEntity> findFirstByInterviewSessionIdOrderByCreatedAtAsc(
        String interviewSessionId
    );

    Optional<AgentChatSessionEntity> findByAgentSessionId(String agentSessionId);

    List<AgentChatSessionEntity> findByInterviewSessionIdOrderByUpdatedAtDesc(String interviewSessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AgentChatSessionEntity s WHERE s.agentSessionId = :agentSessionId")
    Optional<AgentChatSessionEntity> findByAgentSessionIdForUpdate(
        @Param("agentSessionId") String agentSessionId
    );

    List<AgentChatSessionEntity> findByRunStatusAndRunStartedAtBefore(
        AgentRunStatus runStatus, LocalDateTime runStartedAt
    );

    /** 串行化同一面试 Agent 会话的消息序号分配。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AgentChatSessionEntity s WHERE s.interviewSessionId = :interviewSessionId ORDER BY s.createdAt ASC")
    Optional<AgentChatSessionEntity> findByInterviewSessionIdForUpdate(
        @Param("interviewSessionId") String interviewSessionId
    );
}
