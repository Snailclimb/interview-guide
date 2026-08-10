package interview.guide.modules.jdmatch.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.jdmatch.model.JdMatchEntity;
import interview.guide.modules.jdmatch.repository.JdMatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * JD 匹配分析任务生产者
 * 负责发送匹配分析任务到 Redis Stream
 */
@Slf4j
@Component
public class JdMatchStreamProducer extends AbstractStreamProducer<JdMatchStreamProducer.JdMatchTaskPayload> {

    private final JdMatchRepository jdMatchRepository;
    private final TransactionalExecutor transactionalExecutor;

    record JdMatchTaskPayload(Long jdMatchId, String resumeText, String jdTitle, String jdText) {}

    public JdMatchStreamProducer(
        RedisService redisService,
        JdMatchRepository jdMatchRepository,
        TransactionalExecutor transactionalExecutor
    ) {
        super(redisService);
        this.jdMatchRepository = jdMatchRepository;
        this.transactionalExecutor = transactionalExecutor;
    }

    /**
     * 发送匹配分析任务到 Redis Stream
     *
     * @param jdMatchId  匹配分析ID
     * @param resumeText 简历文本
     * @param jdTitle    JD 标题（可为空）
     * @param jdText     JD 文本
     */
    public void sendMatchTask(Long jdMatchId, String resumeText, String jdTitle, String jdText) {
        sendTask(new JdMatchTaskPayload(jdMatchId, resumeText, jdTitle, jdText));
    }

    @Override
    protected String taskDisplayName() {
        return "JD匹配分析";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.JD_MATCH_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(JdMatchTaskPayload payload) {
        return Map.of(
            AsyncTaskStreamConstants.FIELD_JD_MATCH_ID, payload.jdMatchId().toString(),
            AsyncTaskStreamConstants.FIELD_CONTENT, payload.resumeText(),
            AsyncTaskStreamConstants.FIELD_JD_TITLE, payload.jdTitle() != null ? payload.jdTitle() : "",
            AsyncTaskStreamConstants.FIELD_JD_TEXT, payload.jdText(),
            AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(JdMatchTaskPayload payload) {
        return "jdMatchId=" + payload.jdMatchId();
    }

    @Override
    protected void onSendFailed(JdMatchTaskPayload payload, String error) {
        transactionalExecutor.runRequiresNew(
            () -> updateMatchStatus(payload.jdMatchId(), AsyncTaskStatus.FAILED, error));
    }

    /**
     * 更新匹配分析状态
     */
    private void updateMatchStatus(Long jdMatchId, AsyncTaskStatus status, String error) {
        jdMatchRepository.findById(jdMatchId).ifPresent(entity -> {
            entity.setStatus(status);
            if (error != null) {
                entity.setError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            jdMatchRepository.save(entity);
        });
    }
}
