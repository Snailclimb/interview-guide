package interview.guide.modules.resume.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.ResumeAnalysisResponse;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.ScoreDetail;
import interview.guide.modules.interview.model.ResumeAnalysisResponse.Suggestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历评分服务
 * 使用Spring AI调用LLM对简历进行评分和建议
 */
@Service
public class ResumeGradingService {
    
    private static final Logger log = LoggerFactory.getLogger(ResumeGradingService.class);
    
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<ResumeAnalysisResponseDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    
    // 中间DTO用于接收AI响应
    private record ResumeAnalysisResponseDTO(
        int overallScore,
        ScoreDetailDTO scoreDetail,
        String summary,
        List<String> strengths,
        List<SuggestionDTO> suggestions
    ) {}
    
    private record ScoreDetailDTO(
        int contentScore,
        int structureScore,
        int skillMatchScore,
        int expressionScore,
        int projectScore
    ) {}
    
    private record SuggestionDTO(
        String category,
        String priority,
        String issue,
        String recommendation
    ) {}
    
    public ResumeGradingService(
            LlmProviderRegistry llmProviderRegistry,
            StructuredOutputInvoker structuredOutputInvoker,
            ResumeAnalysisProperties properties,
            ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(properties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(properties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.outputConverter = new BeanOutputConverter<>(ResumeAnalysisResponseDTO.class);
    }
    
    /**
     * 分析简历并返回评分和建议
     *
     * <p>失败时直接向上抛出 {@link BusinessException}（{@code RESUME_ANALYSIS_FAILED}），
     * 不再降级成 0 分的占位结果：占位结果会让
     * {@link interview.guide.modules.resume.listener.AnalyzeStreamConsumer} 把任务记为
     * COMPLETED，Stream 重试（{@code retryMessage}）与恢复调度
     * （{@code ResumeAnalysisRecoveryScheduler}）因此永远不会触发，
     * 用户看到的却是一份虚假的 0 分报告。
     *
     * @param resumeText 简历文本内容
     * @return 分析结果
     */
    public ResumeAnalysisResponse analyzeResume(String resumeText) {
        log.info("开始分析简历，文本长度: {} 字符", resumeText.length());
        
        // 加载系统提示词
        String systemPrompt = systemPromptTemplate.render();
        
        // 加载用户提示词并填充变量
        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeText);
        String userPrompt = userPromptTemplate.render(variables);
        
        // 添加格式指令到系统提示词
        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
        
        // 调用AI：StructuredOutputInvoker 内部已按配置重试，重试耗尽后会抛出携带
        // RESUME_ANALYSIS_FAILED 的 BusinessException，这里不再包裹一层无意义的 catch
        ChatClient chatClient = llmProviderRegistry.getDefaultChatClient();
        ResumeAnalysisResponseDTO dto = structuredOutputInvoker.invoke(
            chatClient,
            systemPromptWithFormat,
            userPrompt,
            outputConverter,
            ErrorCode.RESUME_ANALYSIS_FAILED,
            "简历分析失败：",
            "简历分析",
            log
        );
        log.debug("AI响应解析成功: overallScore={}", dto.overallScore());
        
        // 转换为业务对象
        ResumeAnalysisResponse result = convertToResponse(dto, resumeText);
        log.info("简历分析完成，总分: {}", result.overallScore());
        
        return result;
    }
    
    /**
     * 转换DTO为业务对象
     */
    private ResumeAnalysisResponse convertToResponse(ResumeAnalysisResponseDTO dto, String originalText) {
        ScoreDetail scoreDetail = new ScoreDetail(
            dto.scoreDetail().contentScore(),
            dto.scoreDetail().structureScore(),
            dto.scoreDetail().skillMatchScore(),
            dto.scoreDetail().expressionScore(),
            dto.scoreDetail().projectScore()
        );
        
        List<Suggestion> suggestions = dto.suggestions().stream()
            .map(s -> new Suggestion(s.category(), s.priority(), s.issue(), s.recommendation()))
            .toList();
        
        return new ResumeAnalysisResponse(
            dto.overallScore(),
            scoreDetail,
            dto.summary(),
            dto.strengths(),
            suggestions,
            originalText
        );
    }
}
