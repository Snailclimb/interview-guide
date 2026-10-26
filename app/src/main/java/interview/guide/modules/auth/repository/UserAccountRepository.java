package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.UserAccountEntity;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserAccountRepository extends JpaRepository<UserAccountEntity, String> {
    Optional<UserAccountEntity> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<UserAccountEntity> findAllByOrderByCreatedAtDesc();

    long countByRoleIgnoreCaseAndStatusIgnoreCase(String role, String status);
}
