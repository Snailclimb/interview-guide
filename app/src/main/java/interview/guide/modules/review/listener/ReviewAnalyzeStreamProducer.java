package interview.guide.modules.review.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.review.model.ArtifactType;
import interview.guide.modules.review.repository.ReviewArtifactRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 面试复盘分析任务生产者
 * 每种分析类型独立发送一个 Redis Stream 任务
 */
@Slf4j
@Component
public class ReviewAnalyzeStreamProducer extends AbstractStreamProducer<ReviewAnalyzeStreamProducer.ReviewAnalyzePayload> {

    private final ReviewArtifactRepository artifactRepository;

    public ReviewAnalyzeStreamProducer(RedisService redisService,
                                       ReviewArtifactRepository artifactRepository) {
        super(redisService);
        this.artifactRepository = artifactRepository;
    }

    /**
     * 发送单项分析任务到 Redis Stream
     *
     * @param reviewId     复盘记录 ID
     * @param artifactType 分析类型
     */
    public void sendAnalyzeTask(Long reviewId, ArtifactType artifactType) {
        log.info("[Review] Queuing task: reviewId={}, type={}", reviewId, artifactType);
        sendTask(new ReviewAnalyzePayload(reviewId, artifactType));
    }

    @Override
    protected String taskDisplayName() {
        return "面试复盘分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.REVIEW_ANALYZE_STREAM;
    }

    @Override
    protected Map<String, String> buildMessage(ReviewAnalyzePayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_REVIEW_ID, String.valueOf(payload.reviewId()),
            AsyncTaskStreamConstants.FIELD_ARTIFACT_TYPE, payload.artifactType().name(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(ReviewAnalyzePayload payload) {
        return "reviewId=" + payload.reviewId() + ",type=" + payload.artifactType();
    }

    @Override
    protected void onSendFailed(ReviewAnalyzePayload payload, String error) {
        log.error("[Review] Failed to queue task: reviewId={}, type={}, error={}",
            payload.reviewId(), payload.artifactType(), error);
        artifactRepository.findByReviewIdAndType(payload.reviewId(), payload.artifactType())
            .ifPresent(artifact -> {
                artifact.setStatus(AsyncTaskStatus.FAILED);
                artifact.setError(truncateError(error));
                artifactRepository.save(artifact);
            });
    }

    record ReviewAnalyzePayload(Long reviewId, ArtifactType artifactType) {}
}
