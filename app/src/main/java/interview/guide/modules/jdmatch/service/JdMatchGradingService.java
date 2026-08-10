package interview.guide.modules.jdmatch.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.jdmatch.model.JdMatchResult;
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
 * JD 匹配评分服务
 * 使用 Spring AI 调用 LLM 对"简历 x JD"进行匹配度分析与评分
 */
@Service
public class JdMatchGradingService {

    private static final Logger log = LoggerFactory.getLogger(JdMatchGradingService.class);

    private static final String SYSTEM_PROMPT_PATH = "classpath:prompts/jd-match-system.st";
    private static final String USER_PROMPT_PATH = "classpath:prompts/jd-match-user.st";

    private final LlmProviderRegistry llmProviderRegistry;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<JdMatchResponseDTO> outputConverter;

    // 中间 DTO 用于接收 AI 响应
    private record JdMatchResponseDTO(
        int overallScore,
        ScoreDetailDTO scoreDetail,
        String summary,
        List<RequirementDTO> jdRequirements,
        List<String> strengths,
        List<GapDTO> gaps,
        List<SuggestionDTO> suggestions,
        List<String> interviewQuestions
    ) {
    }

    private record ScoreDetailDTO(
        int hardRequirementScore,
        int skillMatchScore,
        int experienceScore,
        int projectScore,
        int softSkillScore
    ) {
    }

    private record RequirementDTO(
        String requirement,
        String category,
        String priority,
        boolean matched,
        String evidence
    ) {
    }

    private record GapDTO(
        String gap,
        String impact,
        String suggestion
    ) {
    }

    private record SuggestionDTO(
        String category,
        String priority,
        String issue,
        String recommendation
    ) {
    }

    public JdMatchGradingService(
        LlmProviderRegistry llmProviderRegistry,
        StructuredOutputInvoker structuredOutputInvoker,
        ResourceLoader resourceLoader
    ) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(SYSTEM_PROMPT_PATH)
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(USER_PROMPT_PATH)
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.outputConverter = new BeanOutputConverter<>(JdMatchResponseDTO.class);
    }

    /**
     * 分析简历与 JD 的匹配度
     *
     * @param resumeText 简历文本内容
     * @param jdTitle    JD 标题 / 岗位名称（可为空）
     * @param jdText     JD 文本内容
     * @return 匹配分析结果
     */
    public JdMatchResult analyzeMatch(String resumeText, String jdTitle, String jdText) {
        log.info("开始JD匹配分析，简历文本长度: {} 字符，JD文本长度: {} 字符",
            resumeText.length(), jdText.length());

        // 加载系统提示词
        String systemPrompt = systemPromptTemplate.render();

        // 加载用户提示词并填充变量
        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeText);
        variables.put("jdTitle", (jdTitle == null || jdTitle.isBlank()) ? "未提供岗位名称" : jdTitle);
        variables.put("jdText", jdText);
        String userPrompt = userPromptTemplate.render(variables);

        // 添加格式指令到系统提示词
        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();

        // 调用 AI
        JdMatchResponseDTO dto;
        try {
            ChatClient chatClient = llmProviderRegistry.getDefaultChatClient();
            dto = structuredOutputInvoker.invoke(
                chatClient,
                systemPromptWithFormat,
                userPrompt,
                outputConverter,
                ErrorCode.JD_MATCH_FAILED,
                "JD匹配分析失败：",
                "JD匹配分析",
                log
            );
            log.debug("AI响应解析成功: overallScore={}", dto.overallScore());
        } catch (Exception e) {
            log.error("JD匹配分析AI调用失败: {}", e.getMessage(), e);
            throw e;
        }

        JdMatchResult result = convertToResult(dto);
        log.info("JD匹配分析完成，总分: {}", result.overallScore());
        return result;
    }

    /**
     * 转换 DTO 为业务对象
     */
    private JdMatchResult convertToResult(JdMatchResponseDTO dto) {
        JdMatchResult.ScoreDetail scoreDetail = new JdMatchResult.ScoreDetail(
            dto.scoreDetail().hardRequirementScore(),
            dto.scoreDetail().skillMatchScore(),
            dto.scoreDetail().experienceScore(),
            dto.scoreDetail().projectScore(),
            dto.scoreDetail().softSkillScore()
        );

        List<JdMatchResult.Requirement> requirements = dto.jdRequirements().stream()
            .map(r -> new JdMatchResult.Requirement(
                r.requirement(), r.category(), r.priority(), r.matched(),
                r.evidence() != null ? r.evidence() : ""
            ))
            .toList();

        List<JdMatchResult.Gap> gaps = dto.gaps().stream()
            .map(g -> new JdMatchResult.Gap(g.gap(), g.impact(), g.suggestion()))
            .toList();

        List<JdMatchResult.Suggestion> suggestions = dto.suggestions().stream()
            .map(s -> new JdMatchResult.Suggestion(
                s.category(), s.priority(), s.issue(), s.recommendation()
            ))
            .toList();

        return new JdMatchResult(
            dto.overallScore(),
            scoreDetail,
            dto.summary(),
            requirements != null ? requirements : List.of(),
            dto.strengths() != null ? dto.strengths() : List.of(),
            gaps != null ? gaps : List.of(),
            suggestions != null ? suggestions : List.of(),
            dto.interviewQuestions() != null ? dto.interviewQuestions() : List.of()
        );
    }
}
