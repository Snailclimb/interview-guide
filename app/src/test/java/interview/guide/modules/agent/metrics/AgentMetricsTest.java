package interview.guide.modules.agent.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Agent 业务指标")
class AgentMetricsTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    @SuppressWarnings("unchecked")
    private AgentMetrics metrics(boolean enabled) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new AgentMetrics(provider, enabled);
    }

    @Test
    @DisplayName("记录任务完成、重试和 Provider 尝试指标")
    void recordsAgentMetrics() {
        AgentMetrics metrics = metrics(true);
        AgentMetrics.RunTracker tracker = metrics.startRun();

        metrics.recordProviderAttempt("primary", "failure");
        metrics.recordRetry("transient");
        metrics.recordProviderAttempt("fallback", "success");
        metrics.finishRun(tracker, "success");

        assertThat(registry.get(AgentMetrics.RUNS).tag("result", "started").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.get(AgentMetrics.RUNS).tag("result", "success").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.get(AgentMetrics.RUN_DURATION).timer().count()).isEqualTo(1L);
        assertThat(registry.get(AgentMetrics.RETRIES).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(AgentMetrics.PROVIDER_ATTEMPTS).tag("role", "fallback")
            .tag("result", "success").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("同一任务只记录一次终态")
    void recordsTerminalStateOnce() {
        AgentMetrics metrics = metrics(true);
        AgentMetrics.RunTracker tracker = metrics.startRun();

        metrics.finishRun(tracker, "failure");
        metrics.finishRun(tracker, "cancelled");

        assertThat(registry.get(AgentMetrics.RUNS).tag("result", "failure").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.find(AgentMetrics.RUNS).tag("result", "cancelled").counter())
            .isNull();
    }

    @Test
    @DisplayName("关闭指标或缺少 Registry 时安全 no-op")
    void noOpWhenDisabledOrRegistryMissing() {
        AgentMetrics disabled = metrics(false);
        disabled.recordRetry("transient");
        disabled.finishRun(disabled.startRun(), "success");
        assertThat(registry.getMeters()).isEmpty();

        AgentMetrics withoutRegistry = new AgentMetrics(null, true);
        withoutRegistry.recordRetry("transient");
        withoutRegistry.finishRun(withoutRegistry.startRun(), "success");
    }

    @Test
    @DisplayName("记录只读工具调用次数和耗时")
    void recordsToolCallMetrics() {
        AgentMetrics metrics = metrics(true);

        metrics.recordToolCall("load_skill", "success", Duration.ofMillis(12));
        metrics.recordToolCall("load_skill", "failure", Duration.ofMillis(4));

        assertThat(registry.get(AgentMetrics.TOOL_CALLS)
            .tag("tool", "load_skill").tag("result", "success").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.get(AgentMetrics.TOOL_CALLS)
            .tag("tool", "load_skill").tag("result", "failure").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.get(AgentMetrics.TOOL_DURATION)
            .tag("tool", "load_skill").tag("result", "success").timer().count())
            .isEqualTo(1L);
    }
}
