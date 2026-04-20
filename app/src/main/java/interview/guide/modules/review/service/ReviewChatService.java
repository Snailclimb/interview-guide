package interview.guide.modules.review.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.review.model.ReviewArtifactEntity;
import interview.guide.modules.review.repository.ReviewArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewChatService {

    private final ReviewArtifactRepository artifactRepository;

    @Transactional
    public ReviewArtifactEntity updateArtifactContent(Long reviewId, Long artifactId, String content) {
        ReviewArtifactEntity artifact = artifactRepository.findById(artifactId)
            .orElseThrow(() -> new BusinessException(
                ErrorCode.ARTIFACT_NOT_FOUND, "复盘产物不存在"));

        if (!artifact.getReview().getId().equals(reviewId)) {
            throw new BusinessException(ErrorCode.ARTIFACT_NOT_FOUND, "复盘产物不属于当前复盘记录");
        }

        if (artifact.getStatus() == AsyncTaskStatus.PENDING
            || artifact.getStatus() == AsyncTaskStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分析进行中，暂不支持编辑");
        }

        artifact.setContent(content);
        artifact.setVersion(artifact.getVersion() + 1);
        artifact = artifactRepository.save(artifact);

        log.info("Updated review artifact content: reviewId={}, artifactId={}, version={}",
            reviewId, artifactId, artifact.getVersion());
        return artifact;
    }
}
