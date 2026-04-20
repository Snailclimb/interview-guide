package interview.guide.modules.review.service;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.review.model.ArtifactType;
import interview.guide.modules.review.model.InterviewReviewEntity;
import interview.guide.modules.review.model.ReviewListItemDTO;
import interview.guide.modules.review.repository.InterviewReviewRepository;
import interview.guide.modules.review.repository.ReviewArtifactRepository;
import interview.guide.modules.review.model.ReviewArtifactEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 面试复盘搜索服务
 * 基于转录稿全文搜索复盘记录
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewSearchService {

    private final InterviewReviewRepository reviewRepository;
    private final ReviewArtifactRepository artifactRepository;

    /**
     * 根据关键词搜索复盘记录（基于转录稿全文模糊匹配）
     *
     * @param query 搜索关键词
     * @return 匹配的复盘列表 DTO
     */
    public List<ReviewListItemDTO> searchReviews(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<InterviewReviewEntity> reviews =
            reviewRepository.searchByTranscriptText(query.trim());

        log.info("搜索复盘记录: query={}, 结果数={}", query, reviews.size());

        return reviews.stream()
            .map(this::toListItemDTO)
            .toList();
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
