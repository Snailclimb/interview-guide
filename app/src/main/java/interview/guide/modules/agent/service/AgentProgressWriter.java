package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Agent 运行进度的异步、去重写入器。
 *
 * <p>模型流线程只更新内存中的最新状态；数据库写入在 boundedElastic 上延迟合并执行，
 * 避免数据库抖动反向阻塞 SSE token。任务结束时会丢弃未提交的旧进度。</p>
 */
@Slf4j
@Component
public class AgentProgressWriter {

    private static final Duration DEFAULT_DEBOUNCE = Duration.ofMillis(100);
    private static final Duration FINISHED_STATE_TTL = Duration.ofMinutes(5);

    private final AgentConversationService conversationService;
    private final Duration debounce;
    private final ConcurrentHashMap<Long, PendingProgress> pending = new ConcurrentHashMap<>();

    public AgentProgressWriter(
        AgentConversationService conversationService,
        @Value("${app.ai.agent.progress-write-debounce-ms:100}") long debounceMillis
    ) {
        this(conversationService, Duration.ofMillis(Math.max(0, debounceMillis)));
    }

    AgentProgressWriter(AgentConversationService conversationService, Duration debounce) {
        this.conversationService = conversationService;
        this.debounce = debounce == null || debounce.isNegative() ? DEFAULT_DEBOUNCE : debounce;
    }

    public void recordStep(Long messageId, String step, String status, String notice) {
        if (messageId == null) {
            return;
        }
        PendingProgress state = pending.computeIfAbsent(messageId, ignored -> new PendingProgress());
        if (state.finished.get()) {
            return;
        }
        state.latestStep.set(new StepProgress(step, status, notice));
        schedule(messageId, state);
    }

    public void recordSources(Long messageId, List<AgentSource> sources) {
        if (messageId == null) {
            return;
        }
        PendingProgress state = pending.computeIfAbsent(messageId, ignored -> new PendingProgress());
        if (state.finished.get()) {
            return;
        }
        state.latestSources.set(sources == null ? List.of() : List.copyOf(sources));
        schedule(messageId, state);
    }

    /** 终态已经由会话服务落库后，丢弃所有迟到的旧进度。 */
    public void finish(Long messageId) {
        if (messageId == null) {
            return;
        }
        PendingProgress state = pending.computeIfAbsent(messageId, ignored -> new PendingProgress());
        if (!state.finished.compareAndSet(false, true)) {
            return;
        }
        state.latestStep.set(null);
        state.latestSources.set(null);
        Mono.delay(FINISHED_STATE_TTL, Schedulers.boundedElastic())
            .subscribe(ignored -> pending.remove(messageId, state));
    }

    private void schedule(Long messageId, PendingProgress state) {
        if (state.finished.get() || !state.scheduled.compareAndSet(false, true)) {
            return;
        }
        Mono.delay(debounce, Schedulers.boundedElastic())
            .subscribe(ignored -> drain(messageId, state), error -> {
                state.scheduled.set(false);
                log.warn("Agent 运行进度异步调度失败: messageId={}", messageId, error);
            });
    }

    private void drain(Long messageId, PendingProgress state) {
        if (state.finished.get()) {
            pending.remove(messageId, state);
            return;
        }

        StepProgress step = state.latestStep.getAndSet(null);
        List<AgentSource> sources = state.latestSources.getAndSet(null);
        try {
            if (step != null && !state.finished.get()) {
                conversationService.updateRunProgress(messageId, step.step(), step.status(), step.notice());
            }
            if (sources != null && !state.finished.get()) {
                conversationService.updateRunSources(messageId, sources);
            }
        } catch (Throwable error) {
            log.warn("Agent 运行进度异步持久化失败: messageId={}", messageId, error);
        } finally {
            state.scheduled.set(false);
            if (state.finished.get()) {
                pending.remove(messageId, state);
            } else if (state.latestStep.get() != null || state.latestSources.get() != null) {
                schedule(messageId, state);
            } else {
                pending.remove(messageId, state);
            }
        }
    }

    private static final class PendingProgress {
        private final AtomicReference<StepProgress> latestStep = new AtomicReference<>();
        private final AtomicReference<List<AgentSource>> latestSources = new AtomicReference<>();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
    }

    private record StepProgress(String step, String status, String notice) {
    }
}
