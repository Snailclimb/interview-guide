package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentTrainingTaskEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface AgentTrainingTaskRepository extends JpaRepository<AgentTrainingTaskEntity, Long> {

    List<AgentTrainingTaskEntity> findTop100ByOwnerKeyOrderByCreatedAtDesc(String ownerKey);

    List<AgentTrainingTaskEntity> findTop100ByOwnerKeyAndStatusOrderByCreatedAtDesc(
        String ownerKey, interview.guide.modules.agent.model.AgentTrainingTaskStatus status
    );

    Optional<AgentTrainingTaskEntity> findBySourceActionId(String sourceActionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AgentTrainingTaskEntity> findByTaskId(String taskId);
}
