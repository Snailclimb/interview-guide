package interview.guide.modules.review.repository;

import interview.guide.modules.review.model.ArtifactType;
import interview.guide.modules.review.model.ReviewArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewArtifactRepository extends JpaRepository<ReviewArtifactEntity, Long> {

    List<ReviewArtifactEntity> findByReviewId(Long reviewId);

    Optional<ReviewArtifactEntity> findByReviewIdAndType(Long reviewId, ArtifactType type);

    void deleteByReviewId(Long reviewId);
}
