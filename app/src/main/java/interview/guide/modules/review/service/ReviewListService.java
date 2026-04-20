package interview.guide.modules.review.service;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.ReviewMapper;
import interview.guide.modules.review.model.ArtifactDTO;
import interview.guide.modules.review.model.ArtifactType;
import interview.guide.modules.review.model.InterviewReviewEntity;
import interview.guide.modules.review.model.ReviewArtifactEntity;
import interview.guide.modules.review.model.ReviewDetailDTO;
import interview.guide.modules.review.model.ReviewListItemDTO;
import interview.guide.modules.review.repository.InterviewReviewRepository;
import interview.guide.modules.review.repository.ReviewArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewListService {

    private final InterviewReviewRepository reviewRepository;
    private final ReviewArtifactRepository artifactRepository;
    private final FileStorageService fileStorageService;
    private final ReviewMapper reviewMapper;
    private final ReviewDomainService reviewDomainService;
    private final TransactionTemplate transactionTemplate;

    public List<ReviewListItemDTO> listReviews(String companyName,
                                               LocalDateTime startDate,
                                               LocalDateTime endDate) {
        List<InterviewReviewEntity> reviews;

        if (companyName != null && !companyName.isBlank() && startDate != null && endDate != null) {
            reviews = reviewRepository.findByCompanyNameContainingIgnoreCaseAndInterviewDateBetween(
                companyName, startDate, endDate);
        } else if (companyName != null && !companyName.isBlank()) {
            reviews = reviewRepository.findByCompanyNameContainingIgnoreCase(companyName);
        } else if (startDate != null && endDate != null) {
            reviews = reviewRepository.findByInterviewDateBetween(startDate, endDate);
        } else {
            reviews = reviewRepository.findAllByOrderByInterviewDateDesc();
        }

        return reviews.stream()
            .map(this::toListItemDTO)
            .toList();
    }

    public ReviewDetailDTO getDetail(Long reviewId) {
        InterviewReviewEntity review = reviewDomainService.findReviewOrThrow(reviewId);
        List<ReviewArtifactEntity> artifacts = artifactRepository.findByReviewId(reviewId);

        ReviewDetailDTO detail = reviewMapper.toDetailDTO(review);
        return new ReviewDetailDTO(
            detail.id(),
            detail.scheduleId(),
            detail.transcriptStorageUrl(),
            detail.transcriptText(),
            detail.companyName(),
            detail.position(),
            detail.roundNumber(),
            detail.interviewDate(),
            detail.status(),
            detail.createdAt(),
            detail.updatedAt(),
            reviewMapper.toArtifactDTOs(artifacts)
        );
    }

    public List<ArtifactDTO> getArtifactStatuses(Long reviewId) {
        reviewDomainService.findReviewOrThrow(reviewId);
        return reviewMapper.toArtifactDTOs(artifactRepository.findByReviewId(reviewId));
    }

    public void deleteReview(Long reviewId) {
        String transcriptStorageKey = transactionTemplate.execute(status -> {
            InterviewReviewEntity review = reviewDomainService.findReviewOrThrow(reviewId);
            artifactRepository.deleteByReviewId(reviewId);
            reviewRepository.delete(review);
            return review.getTranscriptStorageKey();
        });

        if (transcriptStorageKey != null && !transcriptStorageKey.isBlank()) {
            try {
                fileStorageService.deleteReview(transcriptStorageKey);
            } catch (Exception e) {
                log.error("Failed to delete review file after DB deletion: reviewId={}, storageKey={}",
                    reviewId, transcriptStorageKey, e);
            }
        }

        log.info("Deleted review: reviewId={}", reviewId);
    }

    private ReviewListItemDTO toListItemDTO(InterviewReviewEntity review) {
        Long reviewId = review.getId();
        List<ReviewArtifactEntity> artifacts = artifactRepository.findByReviewId(reviewId);

        boolean questionAnalysisDone = artifacts.stream()
            .anyMatch(a -> a.getType() == ArtifactType.QUESTION_ANALYSIS
                && a.getStatus() == AsyncTaskStatus.COMPLETED);
        boolean projectAnalysisDone = artifacts.stream()
            .anyMatch(a -> a.getType() == ArtifactType.PROJECT_ANALYSIS
                && a.getStatus() == AsyncTaskStatus.COMPLETED);
        boolean questionRecordDone = artifacts.stream()
            .anyMatch(a -> a.getType() == ArtifactType.QUESTION_RECORD
                && a.getStatus() == AsyncTaskStatus.COMPLETED);

        return new ReviewListItemDTO(
            reviewId,
            review.getCompanyName(),
            review.getPosition(),
            review.getRoundNumber(),
            review.getInterviewDate(),
            review.getStatus(),
            review.getCreatedAt(),
            questionAnalysisDone,
            projectAnalysisDone,
            questionRecordDone
        );
    }
}
