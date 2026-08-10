package interview.guide.modules.jdmatch.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.jdmatch.model.JdMatchEntity;
import interview.guide.modules.jdmatch.model.JdMatchResult;
import interview.guide.modules.jdmatch.repository.JdMatchRepository;
import interview.guide.modules.jdmatch.service.JdMatchGradingService;
import interview.guide.modules.jdmatch.service.JdMatchPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * JD 匹配分析 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行 AI 匹配分析
 */
@Slf4j
@Component
public class JdMatchStreamConsumer extends AbstractStreamConsumer<JdMatchStreamConsumer.JdMatchPayload> {

    private final JdMatchGradingService gradingService;
    private final JdMatchPersistenceService persistenceService;
    private final JdMatchRepository jdMatchRepository;

    public JdMatchStreamConsumer(
        RedisService redisService,
        JdMatchGradingService gradingService,
        JdMatchPersistenceService persistenceService,
        JdMatchRepository jdMatchRepository
    ) {
        super(redisService);
        this.gradingService = gradingService;
        this.persistenceService = persistenceService;
        this.jdMatchRepository = jdMatchRepository;
    }

    record JdMatchPayload(Long jdMatchId, String resumeText, String jdTitle, String jdText) {}

    @Override
    protected String taskDisplayName() {
        return "JD匹配分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.JD_MATCH_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.JD_MATCH_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.JD_MATCH_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "jdmatch-consumer";
    }

    @Override
    protected JdMatchPayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String jdMatchIdStr = data.get(AsyncTaskStreamConstants.FIELD_JD_MATCH_ID);
        String resumeText = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
        String jdTitle = data.get(AsyncTaskStreamConstants.FIELD_JD_TITLE);
        String jdText = data.get(AsyncTaskStreamConstants.FIELD_JD_TEXT);
        if (jdMatchIdStr == null || resumeText == null || jdText == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new JdMatchPayload(Long.parseLong(jdMatchIdStr), resumeText, jdTitle, jdText);
    }

    @Override
    protected String payloadIdentifier(JdMatchPayload payload) {
        return "jdMatchId=" + payload.jdMatchId();
    }

    @Override
    protected boolean shouldSkip(JdMatchPayload payload) {
        return jdMatchRepository.findById(payload.jdMatchId())
            .map(entity -> entity.getStatus() == AsyncTaskStatus.COMPLETED)
            .orElse(true);
    }

    @Override
    protected void markProcessing(JdMatchPayload payload) {
        updateMatchStatus(payload.jdMatchId(), AsyncTaskStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(JdMatchPayload payload) {
        Long jdMatchId = payload.jdMatchId();
        if (!jdMatchRepository.existsById(jdMatchId)) {
            log.warn("匹配分析已被删除，跳过任务: jdMatchId={}", jdMatchId);
            return;
        }

        JdMatchResult result = gradingService.analyzeMatch(
            payload.resumeText(), payload.jdTitle(), payload.jdText());
        JdMatchEntity entity = jdMatchRepository.findById(jdMatchId).orElse(null);
        if (entity == null) {
            log.warn("匹配分析在处理期间被删除，跳过保存结果: jdMatchId={}", jdMatchId);
            return;
        }
        persistenceService.saveResult(entity, result);
    }

    @Override
    protected void markCompleted(JdMatchPayload payload) {
        updateMatchStatus(payload.jdMatchId(), AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(JdMatchPayload payload, String error) {
        updateMatchStatus(payload.jdMatchId(), AsyncTaskStatus.FAILED, error);
    }

    @Override
    protected void retryMessage(JdMatchPayload payload, int retryCount) {
        Long jdMatchId = payload.jdMatchId();
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_JD_MATCH_ID, jdMatchId.toString(),
                AsyncTaskStreamConstants.FIELD_CONTENT, payload.resumeText(),
                AsyncTaskStreamConstants.FIELD_JD_TITLE, payload.jdTitle() != null ? payload.jdTitle() : "",
                AsyncTaskStreamConstants.FIELD_JD_TEXT, payload.jdText(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.JD_MATCH_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("JD匹配分析任务已重新入队: jdMatchId={}, retryCount={}", jdMatchId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: jdMatchId={}, error={}", jdMatchId, e.getMessage(), e);
            updateMatchStatus(jdMatchId, AsyncTaskStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
        }
    }

    /**
     * 更新匹配分析状态
     */
    private void updateMatchStatus(Long jdMatchId, AsyncTaskStatus status, String error) {
        try {
            jdMatchRepository.findById(jdMatchId).ifPresent(entity -> {
                entity.setStatus(status);
                entity.setError(error);
                jdMatchRepository.save(entity);
                log.debug("匹配分析状态已更新: jdMatchId={}, status={}", jdMatchId, status);
            });
        } catch (Exception e) {
            log.error("更新匹配分析状态失败: jdMatchId={}, status={}, error={}",
                jdMatchId, status, e.getMessage(), e);
        }
    }

}
