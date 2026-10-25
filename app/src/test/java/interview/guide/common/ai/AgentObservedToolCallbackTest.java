package interview.guide.common.ai;

import interview.guide.modules.agent.metrics.AgentMetrics;
import interview.guide.modules.agent.metrics.AgentRunBudgetExceededException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Agent 工具调用观测")
class AgentObservedToolCallbackTest {

    @Test
    @DisplayName("工具成功返回时保留结果并记录成功指标")
    void observesSuccessfulToolCall() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = metrics(registry);
        ToolCallback delegate = mockTool("load_skill", "技能内容");
        AgentObservedToolCallback observed = new AgentObservedToolCallback(delegate, metrics);

        assertThat(observed.call("{\"skill\":\"java\"}")).isEqualTo("技能内容");
        assertThat(registry.get(AgentMetrics.TOOL_CALLS)
            .tag("tool", "load_skill").tag("result", "success").counter().count())
            .isEqualTo(1.0);
    }

    @Test
    @DisplayName("工具失败时保留异常并记录失败指标")
    void observesFailedToolCall() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = metrics(registry);
        ToolCallback delegate = mockTool("load_skill", null);
        when(delegate.call("bad-input")).thenThrow(new IllegalArgumentException("invalid"));
        AgentObservedToolCallback observed = new AgentObservedToolCallback(delegate, metrics);

        assertThatThrownBy(() -> observed.call("bad-input"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid");
        assertThat(registry.get(AgentMetrics.TOOL_CALLS)
            .tag("tool", "load_skill").tag("result", "failure").counter().count())
            .isEqualTo(1.0);
    }

    @Test
    @DisplayName("工具调用达到单次运行预算后停止继续调用")
    void enforcesToolCallBudgetFromToolContext() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = metrics(registry);
        AgentMetrics.RunTracker run = metrics.startRun(4, 1);
        ToolCallback delegate = mockTool("load_skill", "技能内容");
        AgentObservedToolCallback observed = new AgentObservedToolCallback(delegate, metrics);
        org.springframework.ai.chat.model.ToolContext context =
            new org.springframework.ai.chat.model.ToolContext(Map.of(
                AgentMetrics.RUN_TRACKER_TOOL_CONTEXT_KEY, run
            ));
        when(delegate.call("{\"skill\":\"java\"}", context)).thenReturn("技能内容");

        assertThat(observed.call("{\"skill\":\"java\"}", context)).isEqualTo("技能内容");
        assertThatThrownBy(() -> observed.call("{\"skill\":\"java\"}", context))
            .isInstanceOf(AgentRunBudgetExceededException.class)
            .hasMessageContaining("工具调用");
        org.mockito.Mockito.verify(delegate).call("{\"skill\":\"java\"}", context);
    }

    private AgentMetrics metrics(SimpleMeterRegistry registry) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new AgentMetrics(provider, true);
    }

    private ToolCallback mockTool(String name, String result) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call("{\"skill\":\"java\"}")).thenReturn(result);
        return callback;
    }
}
