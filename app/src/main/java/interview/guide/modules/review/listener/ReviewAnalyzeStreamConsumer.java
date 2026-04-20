package interview.guide.modules.review.listener;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.review.model.ArtifactType;
import interview.guide.modules.review.model.InterviewReviewEntity;
import interview.guide.modules.review.model.ReviewArtifactEntity;
import interview.guide.modules.review.model.ReviewStatus;
import interview.guide.modules.review.repository.ReviewArtifactRepository;
import interview.guide.modules.review.service.ReviewDomainService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class ReviewAnalyzeStreamConsumer
    extends AbstractStreamConsumer<ReviewAnalyzeStreamConsumer.ReviewAnalyzePayload> {

    private static final Map<ArtifactType, String> TEMPLATE_PATHS = Map.of(
        ArtifactType.QUESTION_ANALYSIS, "classpath:prompts/review/question-analysis.st",
        ArtifactType.PROJECT_ANALYSIS, "classpath:prompts/review/project-analysis.st",
        ArtifactType.QUESTION_RECORD, "classpath:prompts/review/question-record.st"
    );
    private static final long LLM_TIMEOUT_SECONDS = 300;

    @Override
    protected int concurrency() {
        return 3;
    }

    private final ReviewArtifactRepository artifactRepository;
    private final LlmProviderRegistry llmProviderRegistry;
    private final ResourceLoader resourceLoader;
    private final ReviewDomainService reviewDomainService;

    public ReviewAnalyzeStreamConsumer(
        RedisService redisService,
        ReviewArtifactRepository artifactRepository,
        LlmProviderRegistry llmProviderRegistry,
        ResourceLoader resourceLoader,
        ReviewDomainService reviewDomainService
    ) {
        super(redisService);
        this.artifactRepository = artifactRepository;
        this.llmProviderRegistry = llmProviderRegistry;
        this.resourceLoader = resourceLoader;
        this.reviewDomainService = reviewDomainService;
    }

    record ReviewAnalyzePayload(Long reviewId, ArtifactType artifactType) {}

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupStaleMessages() {
        try {
            RStream<String, String> stream = redisService().getClient()
                .getStream(AsyncTaskStreamConstants.REVIEW_ANALYZE_STREAM, StringCodec.INSTANCE);
            long size = stream.size();
            if (size > 0) {
                log.warn("Found {} stale review analysis messages on startup, cleaning up", size);
                stream.delete();
                redisService().createStreamGroup(
                    AsyncTaskStreamConstants.REVIEW_ANALYZE_STREAM,
                    AsyncTaskStreamConstants.REVIEW_ANALYZE_GROUP
                );
                log.info("Recreated review analysis stream group after cleanup");
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup stale review analysis messages: {}", e.getMessage());
        }
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
    protected String groupName() {
        return AsyncTaskStreamConstants.REVIEW_ANALYZE_GROUP;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.REVIEW_ANALYZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "review-analyze-consumer";
    }

    @Override
    protected ReviewAnalyzePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String reviewIdStr = data.get(AsyncTaskStreamConstants.FIELD_REVIEW_ID);
        String artifactTypeStr = data.get(AsyncTaskStreamConstants.FIELD_ARTIFACT_TYPE);
        if (reviewIdStr == null || artifactTypeStr == null) {
            log.warn("Review analysis message missing fields: messageId={}", messageId);
            return null;
        }
        try {
            return new ReviewAnalyzePayload(Long.valueOf(reviewIdStr), ArtifactType.valueOf(artifactTypeStr));
        } catch (NumberFormatException e) {
            log.warn("Invalid reviewId in review analysis message: messageId={}, reviewId={}",
                messageId, reviewIdStr);
            return null;
        }
    }

    @Override
    protected String payloadIdentifier(ReviewAnalyzePayload payload) {
        return "reviewId=" + payload.reviewId() + ",type=" + payload.artifactType();
    }

    @Override
    protected void markProcessing(ReviewAnalyzePayload payload) {
        log.info("[Review] Marking PROCESSING: reviewId={}, type={}", payload.reviewId(), payload.artifactType());
        InterviewReviewEntity review = reviewDomainService.findReviewOrThrow(payload.reviewId());
        if (review.isAnalysisCancelled()) {
            log.info("[Review] Skipping processing for cancelled review: reviewId={}, type={}",
                payload.reviewId(), payload.artifactType());
            return;
        }
        Optional<ReviewArtifactEntity> artifactOpt =
            artifactRepository.findByReviewIdAndType(payload.reviewId(), payload.artifactType());
        ReviewArtifactEntity artifact = artifactOpt.orElseGet(() ->
            reviewDomainService.newArtifact(review, payload.artifactType()));
        artifact.setStatus(AsyncTaskStatus.PROCESSING);
        artifactRepository.save(artifact);
    }

    @Override
    protected void processBusiness(ReviewAnalyzePayload payload) {
        Long reviewId = payload.reviewId();
        ArtifactType artifactType = payload.artifactType();

        log.info("[Review] >>> Starting analysis: reviewId={}, type={}", reviewId, artifactType);

        InterviewReviewEntity review = reviewDomainService.findReviewOrThrow(reviewId);
        if (review.isAnalysisCancelled()) {
            log.info("[Review] Skipping business processing for cancelled review: reviewId={}, type={}",
                reviewId, artifactType);
            return;
        }
        String transcriptPreview = review.getTranscriptText().length() > 100
            ? review.getTranscriptText().substring(0, 100) + "..."
            : review.getTranscriptText();
        log.info("[Review] Transcript loaded: reviewId={}, length={}, preview={}",
            reviewId, review.getTranscriptText().length(), transcriptPreview);

        // Load and render per-type prompt template
        String templatePath = TEMPLATE_PATHS.get(artifactType);
        String userPrompt = renderTemplate(templatePath, review.getTranscriptText(), artifactType);
        log.info("[Review] Prompt rendered: reviewId={}, type={}, promptLength={}",
            reviewId, artifactType, userPrompt.length());

        // Call LLM
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault("dashscope");
        log.info("[Review] Calling LLM: reviewId={}, type={}", reviewId, artifactType);
        long startTime = System.currentTimeMillis();
        String content = callLlm(chatClient, userPrompt, reviewId, artifactType);
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[Review] LLM responded: reviewId={}, type={}, elapsed={}ms, responseLength={}",
            reviewId, artifactType, elapsed, content.length());

        review = reviewDomainService.findReviewOrThrow(reviewId);
        if (review.isAnalysisCancelled()) {
            log.info("[Review] Discarding analysis result for cancelled review: reviewId={}, type={}",
                reviewId, artifactType);
            return;
        }

        // Save artifact
        InterviewReviewEntity currentReview = review;
        ReviewArtifactEntity artifact = artifactRepository.findByReviewIdAndType(reviewId, artifactType)
            .orElseGet(() -> reviewDomainService.newArtifact(currentReview, artifactType));
        artifact.setContent(content);
        artifact.setVersion(artifact.getVersion() + 1);
        artifactRepository.save(artifact);

        log.info("[Review] <<< Analysis saved: reviewId={}, type={}, version={}",
            reviewId, artifactType, artifact.getVersion());

    }

    @Override
    protected void markCompleted(ReviewAnalyzePayload payload) {
        log.info("[Review] Marked COMPLETED: reviewId={}, type={}", payload.reviewId(), payload.artifactType());
        if (isReviewCancelled(payload.reviewId())) {
            cleanupCancelledArtifact(payload.reviewId(), payload.artifactType());
            return;
        }
        artifactRepository.findByReviewIdAndType(payload.reviewId(), payload.artifactType())
            .ifPresent(artifact -> {
                artifact.setStatus(AsyncTaskStatus.COMPLETED);
                artifactRepository.save(artifact);
            });
        tryMarkReviewAnalyzed(payload.reviewId());
    }

    @Override
    protected void markFailed(ReviewAnalyzePayload payload, String error) {
        log.error("[Review] Marked FAILED: reviewId={}, type={}, error={}",
            payload.reviewId(), payload.artifactType(), error);
        if (isReviewCancelled(payload.reviewId())) {
            cleanupCancelledArtifact(payload.reviewId(), payload.artifactType());
            return;
        }
        artifactRepository.findByReviewIdAndType(payload.reviewId(), payload.artifactType())
            .ifPresent(artifact -> {
                artifact.setStatus(AsyncTaskStatus.FAILED);
                artifact.setError(truncateError(error));
                artifactRepository.save(artifact);
            });
        tryMarkReviewAnalyzed(payload.reviewId());
    }

    @Override
    protected void retryMessage(ReviewAnalyzePayload payload, int retryCount) {
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_REVIEW_ID, String.valueOf(payload.reviewId()),
                AsyncTaskStreamConstants.FIELD_ARTIFACT_TYPE, payload.artifactType().name(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.REVIEW_ANALYZE_STREAM,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("Retried review analysis message: reviewId={}, type={}, retryCount={}",
                payload.reviewId(), payload.artifactType(), retryCount);
        } catch (Exception e) {
            log.error("Failed to retry review analysis message: reviewId={}, type={}, error={}",
                payload.reviewId(), payload.artifactType(), e.getMessage(), e);
            markFailed(payload, "重试消息失败: " + e.getMessage());
        }
    }

    private void tryMarkReviewAnalyzed(Long reviewId) {
        if (isReviewCancelled(reviewId)) {
            return;
        }
        List<ReviewArtifactEntity> artifacts = artifactRepository.findByReviewId(reviewId);
        boolean allDone = artifacts.stream()
            .allMatch(a -> a.getStatus() == AsyncTaskStatus.COMPLETED
                || a.getStatus() == AsyncTaskStatus.FAILED);
        if (allDone) {
            InterviewReviewEntity review = reviewDomainService.findReviewOrThrow(reviewId);
            review.setStatus(ReviewStatus.ANALYZED);
            reviewDomainService.saveReview(review);
            log.info("All review artifacts processed, marked ANALYZED: reviewId={}", reviewId);
        }
    }

    private boolean isReviewCancelled(Long reviewId) {
        return reviewDomainService.findReviewOrThrow(reviewId).isAnalysisCancelled();
    }

    private void cleanupCancelledArtifact(Long reviewId, ArtifactType artifactType) {
        artifactRepository.findByReviewIdAndType(reviewId, artifactType)
            .ifPresent(artifact -> {
                if (artifact.getVersion() > 0) {
                    artifact.setStatus(AsyncTaskStatus.COMPLETED);
                    artifact.setError(null);
                    artifactRepository.save(artifact);
                } else {
                    artifactRepository.delete(artifact);
                }
            });
        log.info("[Review] Cleaned up cancelled artifact: reviewId={}, type={}", reviewId, artifactType);
    }

    private String renderTemplate(String templatePath, String transcriptText, ArtifactType artifactType) {
        try {
            String templateContent = resourceLoader.getResource(templatePath)
                .getContentAsString(StandardCharsets.UTF_8);
            PromptTemplate promptTemplate = new PromptTemplate(templateContent);
            return promptTemplate.render(Map.of("transcript", transcriptText));
        } catch (IOException e) {
            log.error("Failed to load review analysis prompt template: type={}", artifactType, e);
            throw new BusinessException(ErrorCode.REVIEW_ANALYSIS_FAILED,
                "加载分析模板失败: " + artifactType);
        }
    }

    private String callLlm(ChatClient chatClient, String userPrompt, Long reviewId, ArtifactType artifactType) {
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                chatClient.prompt()
                    .user(userPrompt)
                    .call()
                    .content()
            );
            String content = future.get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (content == null || content.isBlank()) {
                throw new BusinessException(ErrorCode.REVIEW_ANALYSIS_FAILED,
                    "AI 分析返回为空: " + artifactType);
            }
            return content;
        } catch (BusinessException e) {
            throw e;
        } catch (TimeoutException e) {
            log.error("Review analysis timeout: reviewId={}, type={}, timeout={}s",
                reviewId, artifactType, LLM_TIMEOUT_SECONDS);
            throw new BusinessException(ErrorCode.REVIEW_ANALYSIS_FAILED,
                "AI 分析超时: " + artifactType);
        } catch (Exception e) {
            log.error("Review analysis failed: reviewId={}, type={}, error={}",
                reviewId, artifactType, e.getMessage(), e);
            throw new BusinessException(ErrorCode.REVIEW_ANALYSIS_FAILED,
                "AI 分析失败: " + e.getMessage());
        }
    }
}
