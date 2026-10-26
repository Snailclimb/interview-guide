package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentAuditEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentAuditEventRepository extends JpaRepository<AgentAuditEventEntity, Long> {
}
