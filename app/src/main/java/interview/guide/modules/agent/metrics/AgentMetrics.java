package interview.guide.modules.agent.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    public static final String MODEL_CALLS = "app.agent.model.calls";
    public static final String MODEL_DURATION = "app.agent.model.duration";
    public static final String TOKENS = "app.agent.tokens";
    public static final String COST = "app.agent.cost";

    private final MeterRegistry registry;
    private final boolean enabled;
    private final AgentCostProperties costProperties;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentMetrics(
        ObjectProvider<MeterRegistry> registryProvider,
        AgentCostProperties costProperties,
        @Value("${app.ai.agent.metrics-enabled:true}") boolean metricsEnabled
    ) {
        this.registry = registryProvider == null ? null : registryProvider.getIfAvailable();
        this.enabled = metricsEnabled && this.registry != null;
        this.costProperties = costProperties == null ? new AgentCostProperties() : costProperties;
    }

    /** 保留轻量构造方式，便于单元测试和没有 Spring 容器的调用方。 */
    public AgentMetrics(
        ObjectProvider<MeterRegistry> registryProvider,
        boolean metricsEnabled
    ) {
        this(registryProvider, new AgentCostProperties(), metricsEnabled);
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

    /** 开始一次实际 Provider 模型调用。一次 Agent 任务可能包含重试和备用模型调用。 */
    public ModelCallTracker startModelCall(String providerId) {
        return new ModelCallTracker(safeTag(providerId, "unknown"), System.nanoTime());
    }

    /** 记录流式响应中的 usage 快照；Provider 可能只在最后一个 chunk 返回 usage。 */
    public void observeModelUsage(
        ModelCallTracker tracker,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String responseModel
    ) {
        if (tracker == null) {
            return;
        }
        int prompt = nonNegative(promptTokens);
        int completion = nonNegative(completionTokens);
        int total = totalTokens == null ? prompt + completion : nonNegative(totalTokens);
        tracker.usage.set(new TokenUsage(prompt, completion, total));
        if (responseModel != null && !responseModel.isBlank()) {
            tracker.model = safeTag(responseModel, "unknown");
        }
    }

    /** 结束一次 Provider 模型调用并发布低基数 Token、调用次数和耗时指标。 */
    public void finishModelCall(ModelCallTracker tracker, String result) {
        if (tracker == null || !tracker.finished.compareAndSet(false, true)) {
            return;
        }
        if (!enabled) {
            return;
        }
        String safeResult = safeTag(result, "unknown");
        registry.counter(MODEL_CALLS,
            "provider", tracker.provider,
            "model", tracker.model,
            "result", safeResult).increment();
        Timer.builder(MODEL_DURATION)
            .tag("provider", tracker.provider)
            .tag("model", tracker.model)
            .tag("result", safeResult)
            .publishPercentileHistogram()
            .register(registry)
            .record(Duration.ofNanos(Math.max(0, System.nanoTime() - tracker.startedNanos)));

        TokenUsage usage = tracker.usage.get();
        if (usage == null) {
            return;
        }
        recordTokens(tracker, "prompt", usage.promptTokens());
        recordTokens(tracker, "completion", usage.completionTokens());
        recordTokens(tracker, "total", usage.totalTokens());
        recordCost(tracker, usage);
    }

    private void recordTokens(ModelCallTracker tracker, String direction, int count) {
        if (count > 0) {
            registry.counter(TOKENS,
                "provider", tracker.provider,
                "model", tracker.model,
                "direction", direction).increment(count);
        }
    }

    private void recordCost(ModelCallTracker tracker, TokenUsage usage) {
        if (!costProperties.isEnabled()) {
            return;
        }
        AgentCostProperties.Pricing pricing = costProperties.find(tracker.provider);
        if (pricing == null || !pricing.configured()) {
            return;
        }
        BigDecimal cost = pricing.calculate(usage.promptTokens(), usage.completionTokens());
        registry.counter(COST,
            "provider", tracker.provider,
            "model", tracker.model,
            "currency", safeTag(pricing.getCurrency(), "USD"))
            .increment(cost.doubleValue());
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private String safeTag(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().length() > 96 ? value.trim().substring(0, 96) : value.trim();
    }

    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
    }

    public static final class ModelCallTracker {
        private final String provider;
        private final long startedNanos;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<TokenUsage> usage = new AtomicReference<>();
        private volatile String model = "unknown";

        private ModelCallTracker(String provider, long startedNanos) {
            this.provider = provider;
            this.startedNanos = startedNanos;
        }
    }

    public static final class RunTracker {
        private final long startedNanos;
        private final AtomicBoolean finished = new AtomicBoolean();

        private RunTracker(long startedNanos) {
            this.startedNanos = startedNanos;
        }
    }
}
