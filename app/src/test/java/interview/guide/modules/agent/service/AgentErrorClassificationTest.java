package interview.guide.modules.agent.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.agent.metrics.AgentMetrics;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Agent 模型错误分类")
class AgentErrorClassificationTest {

    @Test
    @DisplayName("上下文超限给出缩短上下文建议")
    void explainsContextLimit() throws Exception {
        InterviewAgentService service = service();

        assertThat(service.toAgentErrorMessage(new IllegalStateException(
            "maximum context length is 8192 tokens"
        ))).contains("上下文过长").contains("缩短问题");
    }

    @Test
    @DisplayName("认证失败给出 API Key 和权限建议")
    void explainsAuthenticationFailure() throws Exception {
        InterviewAgentService service = service();

        assertThat(service.toAgentErrorMessage(new IllegalStateException("401 invalid_api_key")))
            .contains("API Key").contains("访问权限");
    }

    @Test
    @DisplayName("超时给出重试或切换模型建议")
    void explainsTimeout() throws Exception {
        InterviewAgentService service = service();

        assertThat(service.toAgentErrorMessage(new IllegalStateException("read timed out")))
            .contains("响应超时").contains("切换其他模型");
    }

    @Test
    @DisplayName("繁忙错误仍给出服务繁忙提示")
    void explainsTransientFailure() throws Exception {
        InterviewAgentService service = service();

        assertThat(service.toAgentErrorMessage(new IllegalStateException("429 too many requests")))
            .contains("服务繁忙").contains("稍后重试");
    }

    private InterviewAgentService service() throws Exception {
        ResourceLoader resourceLoader = mock(ResourceLoader.class);
        when(resourceLoader.getResource(anyString())).thenReturn(
            new ByteArrayResource("模板 {context} {skill} {goal} {rag} {question}"
                .getBytes(StandardCharsets.UTF_8))
        );
        return new InterviewAgentService(
            mock(AgentContextService.class),
            mock(AgentConversationService.class),
            mock(LlmProviderRegistry.class),
            mock(PromptSanitizer.class),
            resourceLoader,
            mock(AgentMetrics.class),
            mock(AgentProgressWriter.class),
            new AgentRetryProperties()
        );
    }
}
