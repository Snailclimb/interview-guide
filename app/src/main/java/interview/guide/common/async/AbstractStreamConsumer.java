package interview.guide.common.async;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis Stream 消费者模板基类。
 * <p>
 * 将消费循环、ACK、重试与生命周期管理收敛到统一模板，子类仅关注业务处理逻辑。
 */
@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final RedisService redisService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;

    protected AbstractStreamConsumer(RedisService redisService) {
        this.redisService = redisService;
    }

    protected int concurrency() {
        return 1;
    }

    @PostConstruct
    public void init() {
        int poolSize = concurrency();

        try {
            redisService.createStreamGroup(streamKey(), groupName());
            log.info("[Stream] {} 消费者组已创建或已存在: {}", taskDisplayName(), groupName());
        } catch (Exception e) {
            log.warn("[Stream] {} 创建消费者组异常（可能已存在）: {}", taskDisplayName(), e.getMessage());
        }

        this.executorService = new ThreadPoolExecutor(
            poolSize,
            poolSize,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, threadName());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );

        running.set(true);
        for (int i = 0; i < poolSize; i++) {
            String consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
            executorService.submit(() -> consumeLoop(consumerName));
            log.info("[Stream] {} 消费者线程已启动: consumer={}, concurrency={}",
                taskDisplayName(), consumerName, poolSize);
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
        }
        log.info("[Stream] {} 消费者已关闭, concurrency={}", taskDisplayName(), concurrency());
    }

    private void consumeLoop(String consumerName) {
        log.info("[Stream] {} 消费循环启动: consumer={}", taskDisplayName(), consumerName);
        while (running.get()) {
            try {
                redisService.streamConsumeMessages(
                    streamKey(),
                    groupName(),
                    consumerName,
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS,
                    this::processMessage
                );
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.info("[Stream] {} 消费者线程被中断: consumer={}", taskDisplayName(), consumerName);
                    break;
                }
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    log.warn("[Stream] {} 消费者组不存在，尝试重建: consumer={}", taskDisplayName(), consumerName);
                    try {
                        redisService.createStreamGroup(streamKey(), groupName());
                    } catch (Exception ignored) {
                    }
                }
                log.error("[Stream] {} 消费循环异常: consumer={}, error={}",
                    taskDisplayName(), consumerName, e.getMessage(), e);
            }
        }
        log.info("[Stream] {} 消费循环退出: consumer={}", taskDisplayName(), consumerName);
    }

    private void processMessage(StreamMessageId messageId, Map<String, String> data) {
        T payload = parsePayload(messageId, data);
        if (payload == null) {
            log.warn("[Stream] {} 消息解析为空，ACK 跳过: messageId={}", taskDisplayName(), messageId);
            ackMessage(messageId);
            return;
        }

        int retryCount = parseRetryCount(data);
        log.info("[Stream] {} >>> 收到任务: {}, messageId={}, retryCount={}",
            taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);

        long start = System.currentTimeMillis();
        try {
            long t0 = System.currentTimeMillis();
            markProcessing(payload);
            log.info("[Stream] {} markProcessing done: {} ({}ms)",
                taskDisplayName(), payloadIdentifier(payload), System.currentTimeMillis() - t0);

            t0 = System.currentTimeMillis();
            processBusiness(payload);
            log.info("[Stream] {} processBusiness done: {} ({}ms)",
                taskDisplayName(), payloadIdentifier(payload), System.currentTimeMillis() - t0);

            t0 = System.currentTimeMillis();
            markCompleted(payload);
            log.info("[Stream] {} markCompleted done: {} ({}ms)",
                taskDisplayName(), payloadIdentifier(payload), System.currentTimeMillis() - t0);

            ackMessage(messageId);
            log.info("[Stream] {} <<< 任务完成: {}, total={}ms",
                taskDisplayName(), payloadIdentifier(payload), System.currentTimeMillis() - start);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[Stream] {} <<< 任务失败: {}, elapsed={}ms, error={}",
                taskDisplayName(), payloadIdentifier(payload), elapsed, e.getMessage(), e);
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                retryMessage(payload, retryCount + 1);
            } else {
                markFailed(payload, truncateError(
                    taskDisplayName() + "失败(已重试" + retryCount + "次): " + e.getMessage()
                ));
            }
            ackMessage(messageId);
        }
    }

    protected int parseRetryCount(Map<String, String> data) {
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    private void ackMessage(StreamMessageId messageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId);
            log.debug("[Stream] {} ACK success: messageId={}", taskDisplayName(), messageId);
        } catch (Exception e) {
            log.error("[Stream] {} ACK failed: messageId={}, error={}", taskDisplayName(), messageId, e.getMessage(), e);
        }
    }

    protected RedisService redisService() {
        return redisService;
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);

    protected abstract String payloadIdentifier(T payload);

    protected abstract void markProcessing(T payload);

    protected abstract void processBusiness(T payload);

    protected abstract void markCompleted(T payload);

    protected abstract void markFailed(T payload, String error);

    protected abstract void retryMessage(T payload, int retryCount);
}
