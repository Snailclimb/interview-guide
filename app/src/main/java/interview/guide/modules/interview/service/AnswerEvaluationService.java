package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.CategoryScore;
import interview.guide.modules.interview.model.InterviewReportDTO.QuestionEvaluation;
import interview.guide.modules.interview.model.InterviewReportDTO.ReferenceAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 答案评估服务
 * 评估用户回答并生成面试报告
 */
@Service
public class AnswerEvaluationService {
    
    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);
    
    private final ChatClient chatClient;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<EvaluationReportDTO> outputConverter;
    
    // 中间DTO用于接收AI响应
    private record EvaluationReportDTO(
        int overallScore,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<QuestionEvaluationDTO> questionEvaluations
    ) {}
    
    private record QuestionEvaluationDTO(
        int questionIndex,
        int score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints
    ) {}
    
    public AnswerEvaluationService(
            ChatClient.Builder chatClientBuilder,
            @Value("classpath:prompts/interview-evaluation-system.st") Resource systemPromptResource,
            @Value("classpath:prompts/interview-evaluation-user.st") Resource userPromptResource) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
        this.outputConverter = new BeanOutputConverter<>(EvaluationReportDTO.class);
    }
    
    /**
     * 评估完整面试并生成报告
     */
    public InterviewReportDTO evaluateInterview(String sessionId, String resumeText,
                                                 List<InterviewQuestionDTO> questions) {
        log.info("开始评估面试: {}, 共{}题", sessionId, questions.size());
        
        try {
            // 构建问答记录
            String qaRecords = buildQARecords(questions);
            
            // 简历摘要（限制长度）
            String resumeSummary = resumeText.length() > 500 
                ? resumeText.substring(0, 500) + "..." 
                : resumeText;
            
            // 加载系统提示词
            String systemPrompt = systemPromptTemplate.render();
            
            // 加载用户提示词并填充变量
            Map<String, Object> variables = new HashMap<>();
            variables.put("resumeText", resumeSummary);
            variables.put("qaRecords", qaRecords);
            String userPrompt = userPromptTemplate.render(variables);
            
            // 添加格式指令到系统提示词
            String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
            
            // 调用AI
            EvaluationReportDTO dto;
            try {
                dto = chatClient.prompt()
                    .system(systemPromptWithFormat)
                    .user(userPrompt)
                    .call()
                    .entity(outputConverter);
                log.debug("评估响应解析成功: overallScore={}", dto.overallScore());
            } catch (Exception e) {
                log.error("面试评估AI调用失败: {}", e.getMessage(), e);
                throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, 
                    "面试评估失败：" + e.getMessage());
            }
            
            // 转换为业务对象
            return convertToReport(sessionId, dto, questions);
            
        } catch (BusinessException e) {
            // 重新抛出业务异常
            throw e;
        } catch (Exception e) {
            log.error("面试评估失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, 
                "面试评估失败：" + e.getMessage());
        }
    }
    
    /**
     * 构建问答记录字符串
     */
    private String buildQARecords(List<InterviewQuestionDTO> questions) {
        StringBuilder sb = new StringBuilder();
        for (InterviewQuestionDTO q : questions) {
            sb.append(String.format("问题%d [%s]: %s\n", 
                q.questionIndex() + 1, q.category(), q.question()));
            sb.append(String.format("回答: %s\n\n", 
                q.userAnswer() != null ? q.userAnswer() : "(未回答)"));
        }
        return sb.toString();
    }
    
    /**
     * 转换DTO为业务对象
     */
    private InterviewReportDTO convertToReport(String sessionId, EvaluationReportDTO dto,
                                               List<InterviewQuestionDTO> questions) {
        List<QuestionEvaluation> questionDetails = new ArrayList<>();
        List<ReferenceAnswer> referenceAnswers = new ArrayList<>();
        Map<String, List<Integer>> categoryScoresMap = new HashMap<>();
        
        // 处理问题评估
        List<QuestionEvaluationDTO> evaluations = dto.questionEvaluations();
        for (int i = 0; i < Math.min(evaluations.size(), questions.size()); i++) {
            QuestionEvaluationDTO eval = evaluations.get(i);
            InterviewQuestionDTO q = questions.get(i);
            int qIndex = q.questionIndex();
            
            questionDetails.add(new QuestionEvaluation(
                qIndex, q.question(), q.category(),
                q.userAnswer(), eval.score(), eval.feedback()
            ));
            
            referenceAnswers.add(new ReferenceAnswer(
                qIndex, q.question(), 
                eval.referenceAnswer() != null ? eval.referenceAnswer() : "",
                eval.keyPoints() != null ? eval.keyPoints() : List.of()
            ));
            
            // 收集类别分数
            categoryScoresMap
                .computeIfAbsent(q.category(), k -> new ArrayList<>())
                .add(eval.score());
        }
        
        // 计算各类别平均分
        List<CategoryScore> categoryScores = categoryScoresMap.entrySet().stream()
            .map(e -> new CategoryScore(
                e.getKey(),
                (int) e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                e.getValue().size()
            ))
            .collect(Collectors.toList());
        
        return new InterviewReportDTO(
            sessionId,
            questions.size(),
            dto.overallScore(),
            categoryScores,
            questionDetails,
            dto.overallFeedback(),
            dto.strengths() != null ? dto.strengths() : List.of(),
            dto.improvements() != null ? dto.improvements() : List.of(),
            referenceAnswers
        );
    }
    
    private InterviewReportDTO createErrorReport(String sessionId, List<InterviewQuestionDTO> questions) {
        return new InterviewReportDTO(
            sessionId,
            questions.size(),
            0,
            List.of(),
            questions.stream()
                .map(q -> new QuestionEvaluation(
                    q.questionIndex(), q.question(), q.category(),
                    q.userAnswer(), 0, "评估服务暂时不可用"
                ))
                .collect(Collectors.toList()),
            "评估过程中出现错误，请稍后重试",
            List.of(),
            List.of("请检查AI服务是否正常运行"),
            List.of()
        );
    }
}
