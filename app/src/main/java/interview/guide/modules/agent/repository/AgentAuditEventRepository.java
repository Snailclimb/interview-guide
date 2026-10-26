package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentAuditEventEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentAuditEventRepository extends JpaRepository<AgentAuditEventEntity, Long> {
    List<AgentAuditEventEntity> findTop100ByOwnerKeyOrderByCreatedAtDesc(String ownerKey);
}
