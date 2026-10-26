package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.SecurityAuditEventEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityAuditEventRepository extends JpaRepository<SecurityAuditEventEntity, Long> {
    List<SecurityAuditEventEntity> findTop200ByOrderByCreatedAtDesc();
}
