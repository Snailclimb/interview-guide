package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeEditDraftEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ResumeEditDraftRepository extends JpaRepository<ResumeEditDraftEntity, Long> {
    Optional<ResumeEditDraftEntity> findByResumeId(Long resumeId);
}
