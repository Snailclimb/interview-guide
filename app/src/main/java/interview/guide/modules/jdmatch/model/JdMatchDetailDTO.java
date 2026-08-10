package interview.guide.modules.jdmatch.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * JD 匹配分析详情 DTO
 */
public record JdMatchDetailDTO(
    Long id,
    Long resumeId,
    String resumeFilename,
    String jdTitle,
    String jdText,
    Integer overallScore,
    ScoreDetailDTO scoreDetail,
    String summary,
    List<JdMatchResult.Requirement> jdRequirements,
    List<String> strengths,
    List<JdMatchResult.Gap> gaps,
    List<JdMatchResult.Suggestion> suggestions,
    List<String> interviewQuestions,
    String status,
    String error,
    LocalDateTime createdAt
) {

    /**
     * 各维度评分详情
     */
    public record ScoreDetailDTO(
        Integer hardRequirementScore,
        Integer skillMatchScore,
        Integer experienceScore,
        Integer projectScore,
        Integer softSkillScore
    ) {
    }
}
