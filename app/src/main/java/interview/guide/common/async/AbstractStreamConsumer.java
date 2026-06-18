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
 * Redis Stream 消费者模板 —— 保证所有异步消费者共享一致的消费生命周期。
 * <p>
 * 选择 Redis Stream + 模板方法模式而非简单队列或函数式接口，原因有三：
 * <ol>
 *   <li>Consumer Group 提供显式 ACK 机制，实现至少一次投递语义，消息处理失败后不会丢失；</li>
 *   <li>同一 Group 内的多个消费者可水平扩展，Stream 自动做负载均衡；</li>
 *   <li>模板强制子类只关心业务钩子（解析、处理、状态标记），
 *       基础设施（Consumer Group 创建、轮询循环、ACK 与重试）由本类统一管理，不可覆写。</li>
 * </ol>
 * <p>
 * 工作流程：初始化 → 创建/确认 Consumer Group → 阻塞轮询 → 逐条解析 → 回调子类业务 → ACK → 失败时按 {@link AsyncTaskStreamConstants#MAX_RETRY_COUNT} 重试。
 *
 * @param <T> 消息荷载类型，子类在 {@link #parsePayload} 中反序列化
 */
@Slf4j
public abstract class AbstractStreamConsumer<T> {

    private final RedisService redisService;
    // 使用 AtomicBoolean 而非 volatile，因为 init（主线程）和 shutdown（容器关闭线程）不在同一线程，
    // 需要保证 running 的更新对消费者线程立即可见且不会出现指令重排导致的"停不掉"
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executorService;
    private String consumerName;

    /**
     * @param redisService 基础设施注入，子类不应直接持有 RedisService —— 如需读取辅助状态，
     *                     请使用受保护的 {@link #redisService()} 访问器
     */
    protected AbstractStreamConsumer(RedisService redisService) {
        this.redisService = redisService;
    }

    /**
     * Spring 容器完成 Bean 组装后立即启动消费线程，不等待第一个消息到达。
     * 如果等懒加载则可能在启动高峰期间积压消息，失去 Stream 削峰填谷的优势。
     */
    @PostConstruct
    public void init() {
        // Consumer Name 添加随机后缀：同一服务的多实例部署时实例名相同，但 Redis Consumer Group 中每个消费者须唯一
        this.consumerName = consumerPrefix() + UUID.randomUUID().toString().substring(0, 8);
        // 单线程 + 容量为 0 的队列：保证单消费者内消息严格顺序处理；AbortPolicy 使线程池在异常状态时快速失败，
        // 避免消费者静默消失（整个生命周期仅提交 1 个任务，拒绝意味着无法恢复）
        this.executorService = new ThreadPoolExecutor(
            1,
            1,
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
        executorService.submit(this::startConsumer);
        log.info("{} consumer started: consumerName={}", taskDisplayName(), consumerName);
    }

    /**
     * 容器关闭时先置 running 为 false 让轮询循环自然退出，再关闭线程池。
     * 顺序不可颠倒：先 shutdown 线程池会打断正在处理的消息，导致该消息既未 ACK 也未标记失败，
     * 重启后 XREADGROUP 重投递时无从判断是"未处理"还是"已处理但丢 ACK"。
     */
    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
        }
        log.info("{} consumer stopped: consumerName={}", taskDisplayName(), consumerName);
    }

    /**
     * 启动第一步：确保 Consumer Group 存在（允许抛异常，Group 可能已由其他消费者创建——那是幂等且预期的）。
     * 即使创建失败也继续进入轮询，因为 Group 可能已在上一轮重启前创建好。
     */
    private void startConsumer() {
        try {
            redisService.createStreamGroup(streamKey(), groupName());
            log.info("Redis Stream group is ready: {}", groupName());
        } catch (Exception e) {
            log.warn("Failed to prepare Redis Stream group: groupName={}", groupName(), e);
        }

        consumeLoop();
    }

    /**
     * 长连接轮询。底层 {@link RedisService#streamConsumeMessages} 使用 XREADGROUP 阻塞读取，
     * 并非真正的忙等。唯一非 running 的出口是 {@link #shutdown()} 置标志位，或 JVM 向守护线程发中断。
     */
    private void consumeLoop() {
        while (running.get()) {
            try {
                redisService.streamConsumeMessages(
                    streamKey(),
                    groupName(),
                    consumerName,
                    AsyncTaskStreamConstants.BATCH_SIZE,
                    AsyncTaskStreamConstants.POLL_INTERVAL_MS,
                    AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS,
                    AsyncTaskStreamConstants.PENDING_CLAIM_BATCH_SIZE,
                    this::processMessage
                );
            } catch (Exception e) {
                // 守护线程在 JVM 关闭时收到中断信号而不是 running=false —— 识别后优雅退出，避免日志污染
                if (Thread.currentThread().isInterrupted()) {
                    log.info("Consumer thread interrupted");
                    break;
                }
                log.error("Failed to consume message", e);
            }
        }
    }

    /**
     * 单条消息的处理管线：解析 → 前置标记 → 业务处理 → 完成标记 → ACK。
     * 失败时依据重试次数决定是重新投递还是进入死信（标记 FAILED + ACK 移除）。
     * ACK 时机：无论成功或最终失败都立即 ACK（死信策略是"移除后外部记录"，而非"保留在 Stream 中人工介入"），
     * 避免未 ACK 消息堆积阻塞同 Group 中其他消费者的正常消费。
     */
    private void processMessage(StreamMessageId messageId, Map<String, String> data) {
        T payload;
        try {
            payload = parsePayload(messageId, data);
        } catch (Exception e) {
            Object fields = data == null ? null : data.keySet();
            log.warn("Failed to parse {} stream message, ack and discard: messageId={}, fields={}",
                taskDisplayName(), messageId, fields, e);
            ackMessage(messageId);
            return;
        }

        if (payload == null) {
            ackMessage(messageId);
            return;
        }

        int retryCount = parseRetryCount(data);
        log.info("Processing {} task: payload={}, messageId={}, retryCount={}",
            taskDisplayName(), payloadIdentifier(payload), messageId, retryCount);

        try {
            if (shouldSkip(payload)) {
                ackMessage(messageId);
                log.info("{} task skipped: {}", taskDisplayName(), payloadIdentifier(payload));
                return;
            }
            markProcessing(payload);
            processBusiness(payload);
            markCompleted(payload);
            ackMessage(messageId);
            log.info("{} task completed: {}", taskDisplayName(), payloadIdentifier(payload));
        } catch (Exception e) {
            log.error("{} task failed: {}", taskDisplayName(), payloadIdentifier(payload), e);
            // 自主控制重试计数而非使用 Redis XCLAIM/Autoclaim，因为 Stream 原生的超时重投递时延不可控，
            // 无法保证在合理时间内完成指定次数重试
            if (retryCount < AsyncTaskStreamConstants.MAX_RETRY_COUNT) {
                retryMessage(payload, retryCount + 1);
            } else {
                markFailed(payload, truncateError(
                    taskDisplayName() + " failed after retry " + retryCount + ": " + e.getMessage()
                ));
            }
            ackMessage(messageId);
        }
    }

    /**
     * 从 Stream 消息体解析重试次数。旧版本生产者的消息可能不含此字段，兜底返回 0 表示首次处理。
     */
    protected int parseRetryCount(Map<String, String> data) {
        if (data == null) {
            return 0;
        }
        try {
            return Integer.parseInt(data.getOrDefault(AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 截断错误消息至 500 字符。各模块的失败标记（{@link #markFailed(Object, String)}）通常写入数据库，
     * 而 error 字段长度有限（多为 VARCHAR(500)），超出则导致持久化失败——本应是错误记录的日志反而引入新错误。
     */
    protected String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    /**
     * ACK 失败时吞掉异常而不是传播。因为即使 ACK 失败，消息最终会被 Stream 重新投递给另一个消费者，
     * 同一消息重入时子类的业务处理（{@link #processBusiness}）应保证幂等。
     * 因 ACK 异常而导致消费者线程崩溃，代价远大于偶发的重复处理。
     */
    private void ackMessage(StreamMessageId messageId) {
        try {
            redisService.streamAck(streamKey(), groupName(), messageId);
        } catch (Exception e) {
            log.error("Failed to ack stream message: messageId={}", messageId, e);
        }
    }

    /**
     * 子类若需读取 Redis 中与 Stream 消费相关的辅助状态（如检查对应实体是否存在），可通过此访问器获取。
     * 设计为 protected 方法而非直接 protected 字段，保留将来在访问点上添加横切逻辑（如指标收集）的可能。
     */
    protected RedisService redisService() {
        return redisService;
    }

    // ==================== 子类必须实现的抽象方法 ====================

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerPrefix();

    protected abstract String threadName();

    protected abstract T parsePayload(StreamMessageId messageId, Map<String, String> data);

    protected abstract String payloadIdentifier(T payload);

    protected boolean shouldSkip(T payload) {
        return false;
    }

    protected abstract void markProcessing(T payload);

    protected abstract void processBusiness(T payload);

    protected abstract void markCompleted(T payload);

    protected abstract void markFailed(T payload, String error);

    protected abstract void retryMessage(T payload, int retryCount);
}
