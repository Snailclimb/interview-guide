package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentActionRequestEntity;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentActionRequestRepository extends JpaRepository<AgentActionRequestEntity, Long> {

    Optional<AgentActionRequestEntity> findByOwnerKeyAndIdempotencyKey(
        String ownerKey, String idempotencyKey
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AgentActionRequestEntity a WHERE a.actionId = :actionId")
    Optional<AgentActionRequestEntity> findByActionIdForUpdate(@Param("actionId") String actionId);

    Optional<AgentActionRequestEntity> findByActionId(String actionId);

    List<AgentActionRequestEntity> findTop50ByOwnerKeyAndInterviewSessionIdOrderByCreatedAtDesc(
        String ownerKey, String interviewSessionId
    );

    List<AgentActionRequestEntity> findTop50ByOwnerKeyAndInterviewSessionIdAndAgentSessionIdOrderByCreatedAtDesc(
        String ownerKey, String interviewSessionId, String agentSessionId
    );
}
