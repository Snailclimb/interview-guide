package interview.guide.modules.review.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.review.model.ArtifactType;
import interview.guide.modules.review.model.InterviewReviewEntity;
import interview.guide.modules.review.model.ReviewArtifactEntity;
import interview.guide.modules.review.repository.InterviewReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewDomainService {

    private final InterviewReviewRepository reviewRepository;

    public InterviewReviewEntity findReviewOrThrow(Long reviewId) {
        return reviewRepository.findById(reviewId)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.REVIEW_NOT_FOUND, "面试复盘记录不存在: " + reviewId));
    }

    @Transactional
    public InterviewReviewEntity findReviewForUpdateOrThrow(Long reviewId) {
        return reviewRepository.findWithLockById(reviewId)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.REVIEW_NOT_FOUND, "面试复盘记录不存在: " + reviewId));
    }

    public ReviewArtifactEntity newArtifact(InterviewReviewEntity review, ArtifactType type) {
        ReviewArtifactEntity artifact = new ReviewArtifactEntity();
        artifact.setReview(review);
        artifact.setType(type);
        artifact.setVersion(0);
        artifact.setContent("");
        return artifact;
    }

    public InterviewReviewEntity saveReview(InterviewReviewEntity review) {
        return reviewRepository.save(review);
    }
}
