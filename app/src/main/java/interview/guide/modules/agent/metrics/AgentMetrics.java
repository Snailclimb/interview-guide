package interview.guide.modules.agent.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Agent 业务指标统一封装。
 *
 * <p>只记录低基数标签，不记录会话 ID、用户问题、模型响应或 Provider URL，避免
 * 指标系统变成业务数据存储。Registry 不存在或开关关闭时自动 no-op。</p>
 */
@Component
public class AgentMetrics {

    public static final String RUNS = "app.agent.runs";
    public static final String RUN_DURATION = "app.agent.run.duration";
    public static final String RETRIES = "app.agent.retries";
    public static final String PROVIDER_ATTEMPTS = "app.agent.provider.attempts";
    public static final String TOOL_CALLS = "app.agent.tool.calls";
    public static final String TOOL_DURATION = "app.agent.tool.duration";

    private final MeterRegistry registry;
    private final boolean enabled;

    public AgentMetrics(
        ObjectProvider<MeterRegistry> registryProvider,
        @Value("${app.ai.agent.metrics-enabled:true}") boolean metricsEnabled
    ) {
        this.registry = registryProvider == null ? null : registryProvider.getIfAvailable();
        this.enabled = metricsEnabled && this.registry != null;
    }

    public boolean enabled() {
        return enabled;
    }

    public RunTracker startRun() {
        if (enabled) {
            registry.counter(RUNS, "result", "started").increment();
        }
        return new RunTracker(System.nanoTime());
    }

    public void finishRun(RunTracker tracker, String result) {
        if (tracker == null || !tracker.finished.compareAndSet(false, true)) {
            return;
        }
        if (enabled) {
            registry.counter(RUNS, "result", result).increment();
            Timer.builder(RUN_DURATION)
                .tag("result", result)
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofNanos(Math.max(0, System.nanoTime() - tracker.startedNanos)));
        }
    }

    public void recordRetry(String reason) {
        if (enabled) {
            registry.counter(RETRIES, "reason", reason).increment();
        }
    }

    public void recordProviderAttempt(String role, String result) {
        if (enabled) {
            registry.counter(PROVIDER_ATTEMPTS, "role", role, "result", result).increment();
        }
    }

    /** 记录 Agent 只读工具调用，不记录工具参数或用户内容。 */
    public void recordToolCall(String toolName, String result, Duration duration) {
        if (!enabled) {
            return;
        }
        String safeToolName = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        String safeResult = result == null || result.isBlank() ? "unknown" : result;
        registry.counter(TOOL_CALLS, "tool", safeToolName, "result", safeResult).increment();
        Timer.builder(TOOL_DURATION)
            .tag("tool", safeToolName)
            .tag("result", safeResult)
            .publishPercentileHistogram()
            .register(registry)
            .record(duration == null ? Duration.ZERO : duration);
    }

    public static final class RunTracker {
        private final long startedNanos;
        private final AtomicBoolean finished = new AtomicBoolean();

        private RunTracker(long startedNanos) {
            this.startedNanos = startedNanos;
        }
    }
}
