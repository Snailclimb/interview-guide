package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentRunUsageEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentRunUsageRepository extends JpaRepository<AgentRunUsageEntity, Long> {

    Optional<AgentRunUsageEntity> findByRunId(String runId);

    List<AgentRunUsageEntity> findByOwnerKeyOrderByCreatedAtDesc(String ownerKey);
}
