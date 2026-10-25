package interview.guide.modules.agent.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
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

    @SuppressWarnings("unchecked")
    private AgentMetrics metrics(boolean enabled, AgentCostProperties costProperties) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new AgentMetrics(provider, costProperties, enabled);
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

    @Test
    @DisplayName("记录模型调用耗时和 Provider usage Token")
    void recordsModelUsageMetrics() {
        AgentMetrics metrics = metrics(true);
        AgentMetrics.ModelCallTracker tracker = metrics.startModelCall("pixel");

        metrics.observeModelUsage(tracker, 120, 80, null, "gpt-5.6-luna");
        metrics.finishModelCall(tracker, "success");
        metrics.finishModelCall(tracker, "failure");

        assertThat(registry.get(AgentMetrics.MODEL_CALLS)
            .tag("provider", "pixel")
            .tag("model", "gpt-5.6-luna")
            .tag("result", "success")
            .counter().count()).isEqualTo(1.0);
        assertThat(registry.get(AgentMetrics.MODEL_DURATION)
            .tag("provider", "pixel")
            .tag("model", "gpt-5.6-luna")
            .tag("result", "success")
            .timer().count()).isEqualTo(1L);
        assertThat(registry.get(AgentMetrics.TOKENS)
            .tag("provider", "pixel")
            .tag("model", "gpt-5.6-luna")
            .tag("direction", "prompt")
            .counter().count()).isEqualTo(120.0);
        assertThat(registry.get(AgentMetrics.TOKENS)
            .tag("provider", "pixel")
            .tag("model", "gpt-5.6-luna")
            .tag("direction", "completion")
            .counter().count()).isEqualTo(80.0);
        assertThat(registry.get(AgentMetrics.TOKENS)
            .tag("provider", "pixel")
            .tag("model", "gpt-5.6-luna")
            .tag("direction", "total")
            .counter().count()).isEqualTo(200.0);
        assertThat(registry.find(AgentMetrics.MODEL_CALLS)
            .tag("result", "failure").counter()).isNull();
    }

    @Test
    @DisplayName("Provider 没有 usage 时只记录调用，不伪造 Token")
    void doesNotInventUsageWhenProviderOmitsIt() {
        AgentMetrics metrics = metrics(true);
        AgentMetrics.ModelCallTracker tracker = metrics.startModelCall("provider-without-usage");

        metrics.finishModelCall(tracker, "success");

        assertThat(registry.get(AgentMetrics.MODEL_CALLS).counter().count()).isEqualTo(1.0);
        assertThat(registry.find(AgentMetrics.TOKENS).counter()).isNull();
    }

    @Test
    @DisplayName("显式配置单价时按输入输出 Token 累计费用")
    void recordsConfiguredModelCost() {
        AgentCostProperties costProperties = new AgentCostProperties();
        AgentCostProperties.Pricing pricing = new AgentCostProperties.Pricing();
        pricing.setInputPerMillion(new BigDecimal("2"));
        pricing.setOutputPerMillion(new BigDecimal("3"));
        pricing.setCurrency("CNY");
        costProperties.getProviders().put("pixel", pricing);

        AgentMetrics metrics = metrics(true, costProperties);
        AgentMetrics.ModelCallTracker tracker = metrics.startModelCall("pixel");
        metrics.observeModelUsage(tracker, 120, 80, 200, "gpt-5.6-luna");
        metrics.finishModelCall(tracker, "success");

        assertThat(registry.get(AgentMetrics.COST)
            .tag("provider", "pixel")
            .tag("model", "gpt-5.6-luna")
            .tag("currency", "CNY")
            .counter().count()).isCloseTo(0.00048, offset(0.000000000001));
    }

    @Test
    @DisplayName("聚合一次 Agent 运行的模型调用、Token、耗时和费用")
    void snapshotsRunUsage() {
        AgentCostProperties costProperties = new AgentCostProperties();
        AgentCostProperties.Pricing pricing = new AgentCostProperties.Pricing();
        pricing.setInputPerMillion(new BigDecimal("2"));
        pricing.setOutputPerMillion(new BigDecimal("3"));
        pricing.setCurrency("CNY");
        costProperties.getProviders().put("pixel", pricing);

        AgentMetrics metrics = metrics(true, costProperties);
        AgentMetrics.RunTracker run = metrics.startRun();
        AgentMetrics.ModelCallTracker first = metrics.startModelCall("pixel", run);
        metrics.observeModelUsage(first, 120, 80, 200, "gpt-5.6-luna");
        metrics.finishModelCall(first, "success");
        AgentMetrics.ModelCallTracker retry = metrics.startModelCall("pixel", run);
        metrics.observeModelUsage(retry, 10, 5, 15, "gpt-5.6-luna");
        metrics.finishModelCall(retry, "failure");

        assertThat(metrics.snapshot(run).promptTokens()).isEqualTo(130);
        assertThat(metrics.snapshot(run).completionTokens()).isEqualTo(85);
        assertThat(metrics.snapshot(run).totalTokens()).isEqualTo(215);
        assertThat(metrics.snapshot(run).modelCalls()).isEqualTo(2);
        assertThat(metrics.snapshot(run).cost()).isEqualByComparingTo("0.000515");
        assertThat(metrics.snapshot(run).currency()).isEqualTo("CNY");
        assertThat(metrics.snapshot(run).provider()).isEqualTo("pixel");
        assertThat(metrics.snapshot(run).model()).isEqualTo("gpt-5.6-luna");
    }
}
