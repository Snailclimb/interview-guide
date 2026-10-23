package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentUserMemoryEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentUserMemoryRepository extends JpaRepository<AgentUserMemoryEntity, Long> {
    Optional<AgentUserMemoryEntity> findByMemoryKey(String memoryKey);
    void deleteByMemoryKey(String memoryKey);
}
