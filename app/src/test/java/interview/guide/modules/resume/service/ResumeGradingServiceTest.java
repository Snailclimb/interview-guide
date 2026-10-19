package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 回归测试：简历分析失败时必须向上抛出异常。
 *
 * <p>修复前 {@code analyzeResume} 在失败时返回 0 分占位结果，消费者据此把任务记为
 * COMPLETED，导致 Stream 重试与恢复调度双双失效。这里锁定"失败必须抛出"这一契约，
 * 防止再次退化成静默降级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("简历评分服务测试")
class ResumeGradingServiceTest {

    @Mock
    private LlmProviderRegistry llmProviderRegistry;
    @Mock
    private StructuredOutputInvoker structuredOutputInvoker;
    @Mock
    private ResourceLoader resourceLoader;
    @Mock
    private Resource resource;

    private ResumeGradingService service;

    @BeforeEach
    void setUp() throws Exception {
        when(resourceLoader.getResource(anyString())).thenReturn(resource);
        when(resource.getContentAsString(StandardCharsets.UTF_8)).thenReturn("测试提示词");
        service = new ResumeGradingService(
            llmProviderRegistry, structuredOutputInvoker, new ResumeAnalysisProperties(), resourceLoader);
    }

    @Test
    @DisplayName("LLM 调用失败时抛出业务异常，而不是返回 0 分占位结果")
    void shouldPropagateGradingFailureInsteadOfReturningDegradedResult() {
        when(structuredOutputInvoker.invoke(any(), anyString(), anyString(), any(), any(),
            anyString(), anyString(), any()))
            .thenThrow(new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "简历分析失败"));

        assertThatThrownBy(() -> service.analyzeResume("简历正文"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("简历分析失败");
    }

    @Test
    @DisplayName("非业务异常也不再被吞成占位结果")
    void shouldPropagateUnexpectedFailure() {
        when(structuredOutputInvoker.invoke(any(), anyString(), anyString(), any(), any(),
            anyString(), anyString(), any()))
            .thenThrow(new IllegalStateException("连接被重置"));

        assertThatThrownBy(() -> service.analyzeResume("简历正文"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("连接被重置");
    }
}
