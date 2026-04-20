package interview.guide.modules.review.repository;

import interview.guide.modules.review.model.InterviewReviewEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InterviewReviewRepository extends JpaRepository<InterviewReviewEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InterviewReviewEntity> findWithLockById(Long id);

    List<InterviewReviewEntity> findByCompanyNameContainingIgnoreCase(String companyName);

    List<InterviewReviewEntity> findByInterviewDateBetween(LocalDateTime start, LocalDateTime end);

    List<InterviewReviewEntity> findByCompanyNameContainingIgnoreCaseAndInterviewDateBetween(
            String companyName, LocalDateTime start, LocalDateTime end);

    List<InterviewReviewEntity> findAllByOrderByInterviewDateDesc();

    @Query(value = "SELECT r.* FROM interview_review r " +
            "WHERE to_tsvector('simple', coalesce(r.transcript_text, '')) @@ plainto_tsquery('simple', :query)",
            nativeQuery = true)
    List<InterviewReviewEntity> searchByTranscriptText(@Param("query") String query);
}
