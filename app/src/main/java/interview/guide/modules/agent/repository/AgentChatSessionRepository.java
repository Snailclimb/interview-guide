package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentChatSessionEntity;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentChatSessionRepository extends JpaRepository<AgentChatSessionEntity, Long> {

    Optional<AgentChatSessionEntity> findByInterviewSessionId(String interviewSessionId);

    /** 串行化同一面试 Agent 会话的消息序号分配。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AgentChatSessionEntity s WHERE s.interviewSessionId = :interviewSessionId")
    Optional<AgentChatSessionEntity> findByInterviewSessionIdForUpdate(
        @Param("interviewSessionId") String interviewSessionId
    );
}
