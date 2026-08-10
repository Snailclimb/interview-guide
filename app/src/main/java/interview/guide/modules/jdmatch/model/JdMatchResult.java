package interview.guide.modules.jdmatch.model;

import java.util.List;

/**
 * JD 匹配分析业务结果对象（AI 结构化输出）
 */
public record JdMatchResult(
    // 匹配总分 (0-100)
    int overallScore,

    // 各维度评分
    ScoreDetail scoreDetail,

    // 匹配结论摘要
    String summary,

    // JD 核心要求清单
    List<Requirement> jdRequirements,

    // 匹配优势点
    List<String> strengths,

    // 差距与风险
    List<Gap> gaps,

    // 针对该JD的简历优化建议
    List<Suggestion> suggestions,

    // 可能被追问的面试问题
    List<String> interviewQuestions
) {

    /**
     * 各维度评分详情
     */
    public record ScoreDetail(
        int hardRequirementScore,  // 硬性要求匹配度 (0-25)
        int skillMatchScore,       // 技能栈匹配度 (0-25)
        int experienceScore,       // 经验与业务匹配度 (0-20)
        int projectScore,          // 项目经历匹配度 (0-20)
        int softSkillScore         // 软素质匹配度 (0-10)
    ) {
    }

    /**
     * JD 核心要求
     */
    public record Requirement(
        String requirement,  // 具体要求描述
        String category,     // 类别：硬性要求/技能/经验/项目/软素质
        String priority,     // 优先级：CORE / NORMAL
        boolean matched,     // 简历中是否体现
        String evidence      // 简历中的对应证据（未体现则为空字符串）
    ) {
    }

    /**
     * 差距与风险
     */
    public record Gap(
        String gap,        // 差距描述
        String impact,     // 影响：高/中/低
        String suggestion  // 弥补建议
    ) {
    }

    /**
     * 简历优化建议
     */
    public record Suggestion(
        String category,        // 类别：技能/项目/表达/格式
        String priority,        // 优先级：高/中/低
        String issue,           // 问题描述
        String recommendation   // 具体改进建议
    ) {
    }
}
