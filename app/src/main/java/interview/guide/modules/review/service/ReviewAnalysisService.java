package interview.guide.modules.review.service;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.review.listener.ReviewAnalyzeStreamProducer;
import interview.guide.modules.review.model.ArtifactType;
import interview.guide.modules.review.model.InterviewReviewEntity;
import interview.guide.modules.review.model.ReviewArtifactEntity;
import interview.guide.modules.review.model.ReviewStatus;
import interview.guide.modules.review.repository.ReviewArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewAnalysisService {

    private final ReviewArtifactRepository artifactRepository;
    private final ReviewAnalyzeStreamProducer reviewAnalyzeProducer;
    private final ReviewDomainService reviewDomainService;

    @Transactional
    public ReviewArtifactEntity analyze(Long reviewId, ArtifactType type, String provider) {
        InterviewReviewEntity review = reviewDomainService.findReviewForUpdateOrThrow(reviewId);
        review.setAnalysisCancelled(false);
        review.setStatus(ReviewStatus.DRAFT);
        reviewDomainService.saveReview(review);

        boolean hasProcessing = false;
        for (ArtifactType artifactType : ArtifactType.values()) {
            ReviewArtifactEntity artifact = ensureArtifact(review, artifactType);
            if (artifact.getStatus() == AsyncTaskStatus.PROCESSING) {
                hasProcessing = true;
            }
        }

        if (!hasProcessing) {
            for (ArtifactType artifactType : ArtifactType.values()) {
                ReviewArtifactEntity artifact = ensureArtifact(review, artifactType);
                artifact.setStatus(AsyncTaskStatus.PENDING);
                artifact.setError(null);
                artifactRepository.save(artifact);
            }
            registerAfterCommitEnqueue(reviewId);
            log.info("Queued review analysis: reviewId={}", reviewId);
        }

        ReviewArtifactEntity targetArtifact = ensureArtifact(review, type);
        if (targetArtifact.getStatus() == null) {
            targetArtifact.setStatus(AsyncTaskStatus.PENDING);
            artifactRepository.save(targetArtifact);
        }
        return targetArtifact;
    }

    @Transactional
    public void cancelAnalysis(Long reviewId) {
        InterviewReviewEntity review = reviewDomainService.findReviewForUpdateOrThrow(reviewId);
        review.setAnalysisCancelled(true);
        reviewDomainService.saveReview(review);
        for (ArtifactType type : ArtifactType.values()) {
            artifactRepository.findByReviewIdAndType(reviewId, type)
                .ifPresent(artifact -> {
                    if (artifact.getStatus() == AsyncTaskStatus.PROCESSING
                        || artifact.getStatus() == AsyncTaskStatus.PENDING) {
                        if (artifact.getVersion() > 0) {
                            artifact.setStatus(AsyncTaskStatus.COMPLETED);
                            artifact.setError(null);
                            artifactRepository.save(artifact);
                        } else {
                            artifactRepository.delete(artifact);
                        }
                    }
                });
        }
        log.info("Cancelled review analysis: reviewId={}", reviewId);
    }

    private ReviewArtifactEntity ensureArtifact(InterviewReviewEntity review, ArtifactType artifactType) {
        return artifactRepository.findByReviewIdAndType(review.getId(), artifactType)
            .orElseGet(() -> artifactRepository.save(reviewDomainService.newArtifact(review, artifactType)));
    }

    private void registerAfterCommitEnqueue(Long reviewId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (ArtifactType artifactType : ArtifactType.values()) {
                    reviewAnalyzeProducer.sendAnalyzeTask(reviewId, artifactType);
                }
            }
        });
    }
}
