package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentChatSessionEntity;
import java.util.Collection;
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

    List<AgentChatSessionEntity> findAllByAgentSessionIdInAndOwnerKey(
        Collection<String> agentSessionIds, String ownerKey
    );

    List<AgentChatSessionEntity> findByInterviewSessionIdOrderByUpdatedAtDesc(String interviewSessionId);

    /** 已登录列表只读取当前用户最近的未归档线程，避免加载其他用户和历史大字段。 */
    List<AgentConversationSummaryProjection> findTop50ByOwnerKeyAndInterviewSessionIdAndArchivedFalseOrderByUpdatedAtDesc(
        String ownerKey, String interviewSessionId
    );

    interface AgentConversationSummaryProjection {
        String getAgentSessionId();
        String getInterviewSessionId();
        String getTitle();
        AgentRunStatus getRunStatus();
        LocalDateTime getCreatedAt();
        LocalDateTime getUpdatedAt();
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AgentChatSessionEntity s WHERE s.agentSessionId = :agentSessionId")
    Optional<AgentChatSessionEntity> findByAgentSessionIdForUpdate(
        @Param("agentSessionId") String agentSessionId
    );

    List<AgentChatSessionEntity> findByRunStatusAndRunStartedAtBefore(
        AgentRunStatus runStatus, LocalDateTime runStartedAt
    );

    /** 无线程 ID 的写入请求只锁最早创建的默认线程。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<AgentChatSessionEntity> findTop1ByInterviewSessionIdOrderByCreatedAtAsc(
        String interviewSessionId
    );

    /** 旧测试替身和迁移期调用方的兼容入口。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AgentChatSessionEntity s WHERE s.interviewSessionId = :interviewSessionId ORDER BY s.createdAt ASC")
    Optional<AgentChatSessionEntity> findByInterviewSessionIdForUpdate(
        @Param("interviewSessionId") String interviewSessionId
    );
}
