package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO.AnswerDetail;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 语音面试评估服务
 * 复用 UnifiedEvaluationService 的分批评估 + 结构化输出 + 降级兜底
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoiceInterviewEvaluationService {

    private final UnifiedEvaluationService unifiedEvaluationService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final VoiceInterviewEvaluationRepository evaluationRepository;
    private final VoiceInterviewMessageRepository messageRepository;
    private final VoiceInterviewSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final InterviewSkillService skillService;

    /**
     * 生成语音面试评估（由异步消费者调用）
     * LLM 调用在事务外执行，仅 DB 写入在事务内
     */
    public void generateEvaluation(Long sessionId) {
        try {
            log.info("开始生成语音面试评估: sessionId={}", sessionId);

            VoiceInterviewSessionEntity session = getSession(sessionId);
            List<VoiceInterviewMessageEntity> messages = messageRepository
                .findBySessionIdOrderBySequenceNumAsc(sessionId);

            if (messages.isEmpty()) {
                log.warn("语音面试会话无对话记录，生成空评估结果: sessionId={}", sessionId);
                saveEmptyEvaluationTransactional(sessionId, session);
                return;
            }

            List<QaRecord> qaRecords = buildQaRecords(messages);

            String provider = session.getLlmProvider();
            ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

            String sessionIdStr = String.valueOf(sessionId);
            String referenceContext = skillService.buildEvaluationReferenceSectionSafe(session.getSkillId());
            EvaluationReport report = unifiedEvaluationService.evaluate(
                chatClient, sessionIdStr, qaRecords, null, referenceContext);

            saveEvaluationTransactional(sessionId, session, report);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成语音面试评估失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "生成评估失败: " + e.getMessage());
        }
    }

    public VoiceEvaluationDetailDTO getEvaluation(Long sessionId) {
        VoiceInterviewEvaluationEntity evaluation = evaluationRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_EVALUATION_NOT_FOUND,
                "评估结果不存在: " + sessionId));

        return buildDetailDTO(evaluation);
    }

    private List<QaRecord> buildQaRecords(List<VoiceInterviewMessageEntity> messages) {
        List<QaRecord> records = new ArrayList<>();
        int index = 0;

        for (VoiceInterviewMessageEntity msg : messages) {
            String aiText = msg.getAiGeneratedText();
            String userText = msg.getUserRecognizedText();

            if ((aiText != null && !aiText.isBlank()) || (userText != null && !userText.isBlank())) {
                records.add(new QaRecord(
                    index,
                    aiText != null ? aiText.trim() : "",
                    inferCategory(aiText),
                    userText != null && !userText.isBlank() ? userText.trim() : null
                ));
                index++;
            }
        }

        return records;
    }

    private String inferCategory(String aiText) {
        if (aiText == null) return "综合";
        if (aiText.contains("项目") || aiText.contains("实习") || aiText.contains("工作经历")) return "项目深挖";
        if (aiText.contains("自我介绍") || aiText.contains("介绍一下自己")) return "自我介绍";
        if (aiText.contains("职业规划") || aiText.contains("为什么") || aiText.contains("优缺点")) return "HR问题";
        return "技术问题";
    }

    @Transactional
    public void saveEvaluationTransactional(Long sessionId, VoiceInterviewSessionEntity session,
                                 EvaluationReport report) {
        try {
            List<EvaluationReport.QuestionEvaluation> questionItems = report.questionDetails();
            List<EvaluationReport.ReferenceAnswer> refAnswerItems = report.referenceAnswers();

            VoiceInterviewEvaluationEntity entity = VoiceInterviewEvaluationEntity.builder()
                .sessionId(sessionId)
                .overallScore(report.overallScore())
                .overallFeedback(report.overallFeedback())
                .questionEvaluationsJson(objectMapper.writeValueAsString(questionItems))
                .strengthsJson(objectMapper.writeValueAsString(report.strengths()))
                .improvementsJson(objectMapper.writeValueAsString(report.improvements()))
                .referenceAnswersJson(objectMapper.writeValueAsString(refAnswerItems))
                .interviewerRole(session.getRoleType())
                .interviewDate(session.getStartTime())
                .build();

            evaluationRepository.save(entity);
            log.info("评估结果已保存: sessionId={}, score={}", sessionId, entity.getOverallScore());
        } catch (Exception e) {
            log.error("保存评估结果失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "保存评估失败: " + e.getMessage());
        }
    }

    @Transactional
    public void saveEmptyEvaluationTransactional(Long sessionId, VoiceInterviewSessionEntity session) {
        try {
            VoiceInterviewEvaluationEntity entity = evaluationRepository.findBySessionId(sessionId)
                .orElseGet(() -> VoiceInterviewEvaluationEntity.builder().sessionId(sessionId).build());

            entity.setOverallScore(0);
            entity.setOverallFeedback("本次语音面试未形成有效对话记录，暂无可评估内容。");
            entity.setQuestionEvaluationsJson("[]");
            entity.setStrengthsJson("[]");
            entity.setImprovementsJson("[\"请先完成至少一轮有效问答后再生成评估。\"]");
            entity.setReferenceAnswersJson("[]");
            entity.setInterviewerRole(session.getRoleType());
            entity.setInterviewDate(session.getStartTime());

            evaluationRepository.save(entity);
            log.info("空评估结果已保存: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("保存空评估结果失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "保存空评估失败: " + e.getMessage());
        }
    }

    private VoiceEvaluationDetailDTO buildDetailDTO(VoiceInterviewEvaluationEntity entity) {
        try {
            List<EvaluationReport.QuestionEvaluation> questionItems = objectMapper.readValue(
                entity.getQuestionEvaluationsJson(),
                new TypeReference<List<EvaluationReport.QuestionEvaluation>>() {}
            );

            List<String> strengths = objectMapper.readValue(
                entity.getStrengthsJson(),
                new TypeReference<List<String>>() {}
            );

            List<String> improvements = objectMapper.readValue(
                entity.getImprovementsJson(),
                new TypeReference<List<String>>() {}
            );

            List<EvaluationReport.ReferenceAnswer> refAnswers = objectMapper.readValue(
                entity.getReferenceAnswersJson(),
                new TypeReference<List<EvaluationReport.ReferenceAnswer>>() {}
            );

            Map<Integer, EvaluationReport.ReferenceAnswer> refMap = refAnswers.stream()
                .collect(Collectors.toMap(
                    EvaluationReport.ReferenceAnswer::questionIndex, r -> r, (a, b) -> a));

            List<AnswerDetail> answers = new ArrayList<>();
            for (EvaluationReport.QuestionEvaluation q : questionItems) {
                EvaluationReport.ReferenceAnswer ref = refMap.get(q.questionIndex());
                answers.add(AnswerDetail.builder()
                    .questionIndex(q.questionIndex())
                    .question(q.question())
                    .category(q.category())
                    .userAnswer(q.userAnswer())
                    .score(q.score())
                    .feedback(q.feedback())
                    .referenceAnswer(ref != null ? ref.referenceAnswer() : null)
                    .keyPoints(ref != null ? ref.keyPoints() : null)
                    .build());
            }

            return VoiceEvaluationDetailDTO.builder()
                .sessionId(entity.getSessionId())
                .totalQuestions(answers.size())
                .overallScore(entity.getOverallScore())
                .overallFeedback(entity.getOverallFeedback())
                .strengths(strengths)
                .improvements(improvements)
                .answers(answers)
                .build();

        } catch (Exception e) {
            log.error("构建评估详情失败: sessionId={}", entity.getSessionId(), e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "构建评估结果失败: " + e.getMessage());
        }
    }

    private VoiceInterviewSessionEntity getSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND,
                "语音面试会话不存在: " + sessionId));
    }
}
