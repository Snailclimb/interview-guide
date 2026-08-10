package interview.guide.modules.jdmatch.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.jdmatch.model.JdMatchDetailDTO;
import interview.guide.modules.jdmatch.model.JdMatchEntity;
import interview.guide.modules.jdmatch.model.JdMatchListItemDTO;
import interview.guide.modules.jdmatch.model.JdMatchResult;
import interview.guide.modules.jdmatch.repository.JdMatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * JD 匹配分析持久化服务
 * 负责保存分析结果、实体到 DTO 的转换
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JdMatchPersistenceService {

    private final JdMatchRepository jdMatchRepository;
    private final ObjectMapper objectMapper;

    /**
     * 保存匹配分析结果到实体
     */
    @Transactional(rollbackFor = Exception.class)
    public JdMatchEntity saveResult(JdMatchEntity entity, JdMatchResult result) {
        try {
            entity.setOverallScore(result.overallScore());
            entity.setHardRequirementScore(result.scoreDetail().hardRequirementScore());
            entity.setSkillMatchScore(result.scoreDetail().skillMatchScore());
            entity.setExperienceScore(result.scoreDetail().experienceScore());
            entity.setProjectScore(result.scoreDetail().projectScore());
            entity.setSoftSkillScore(result.scoreDetail().softSkillScore());
            entity.setSummary(result.summary());
            entity.setJdRequirementsJson(objectMapper.writeValueAsString(result.jdRequirements()));
            entity.setStrengthsJson(objectMapper.writeValueAsString(result.strengths()));
            entity.setGapsJson(objectMapper.writeValueAsString(result.gaps()));
            entity.setSuggestionsJson(objectMapper.writeValueAsString(result.suggestions()));
            entity.setInterviewQuestionsJson(objectMapper.writeValueAsString(result.interviewQuestions()));

            JdMatchEntity saved = jdMatchRepository.save(entity);
            log.info("JD匹配分析结果已保存: id={}, resumeId={}, score={}",
                saved.getId(), entity.getResumeId(), result.overallScore());
            return saved;
        } catch (JacksonException e) {
            log.error("序列化JD匹配结果失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.JD_MATCH_FAILED, "保存匹配分析结果失败");
        }
    }

    /**
     * 实体转换为列表项 DTO
     */
    public JdMatchListItemDTO toListItemDTO(JdMatchEntity entity) {
        return new JdMatchListItemDTO(
            entity.getId(),
            entity.getResumeId(),
            entity.getResumeFilename(),
            entity.getJdTitle(),
            entity.getOverallScore(),
            entity.getStatus() != null ? entity.getStatus().name() : null,
            entity.getError(),
            entity.getCreatedAt()
        );
    }

    /**
     * 实体转换为详情 DTO
     */
    public JdMatchDetailDTO toDetailDTO(JdMatchEntity entity) {
        try {
            List<JdMatchResult.Requirement> requirements = objectMapper.readValue(
                entity.getJdRequirementsJson() != null ? entity.getJdRequirementsJson() : "[]",
                new TypeReference<>() {
                }
            );
            List<String> strengths = objectMapper.readValue(
                entity.getStrengthsJson() != null ? entity.getStrengthsJson() : "[]",
                new TypeReference<>() {
                }
            );
            List<JdMatchResult.Gap> gaps = objectMapper.readValue(
                entity.getGapsJson() != null ? entity.getGapsJson() : "[]",
                new TypeReference<>() {
                }
            );
            List<JdMatchResult.Suggestion> suggestions = objectMapper.readValue(
                entity.getSuggestionsJson() != null ? entity.getSuggestionsJson() : "[]",
                new TypeReference<>() {
                }
            );
            List<String> interviewQuestions = objectMapper.readValue(
                entity.getInterviewQuestionsJson() != null ? entity.getInterviewQuestionsJson() : "[]",
                new TypeReference<>() {
                }
            );

            JdMatchDetailDTO.ScoreDetailDTO scoreDetail = new JdMatchDetailDTO.ScoreDetailDTO(
                entity.getHardRequirementScore(),
                entity.getSkillMatchScore(),
                entity.getExperienceScore(),
                entity.getProjectScore(),
                entity.getSoftSkillScore()
            );

            return new JdMatchDetailDTO(
                entity.getId(),
                entity.getResumeId(),
                entity.getResumeFilename(),
                entity.getJdTitle(),
                entity.getJdText(),
                entity.getOverallScore(),
                scoreDetail,
                entity.getSummary(),
                requirements,
                strengths,
                gaps,
                suggestions,
                interviewQuestions,
                entity.getStatus() != null ? entity.getStatus().name() : null,
                entity.getError(),
                entity.getCreatedAt()
            );
        } catch (JacksonException e) {
            log.error("反序列化JD匹配结果失败: id={}, error={}", entity.getId(), e.getMessage());
            throw new BusinessException(ErrorCode.JD_MATCH_FAILED, "读取匹配分析结果失败");
        }
    }
}
