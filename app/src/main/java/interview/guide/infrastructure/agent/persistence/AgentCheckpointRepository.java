package interview.guide.infrastructure.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentCheckpointRepository extends JpaRepository<AgentCheckpointEntity, String> {

  Optional<AgentCheckpointEntity> findByRunId(String runId);
}
