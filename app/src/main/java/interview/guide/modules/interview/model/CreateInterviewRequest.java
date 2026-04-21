package interview.guide.modules.interview.model;

import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 创建面试会话请求
 */
public record CreateInterviewRequest(
    String resumeText,      // 简历文本内容（可选，无简历时为通用面试）

    @Min(value = 3, message = "题目数量最少3题")
    @Max(value = 20, message = "题目数量最多20题")
    int questionCount,      // 面试题目数量 (3-20)

    Long resumeId,          // 简历ID（可选，无简历时不传）

    Boolean forceCreate,    // 是否强制创建新会话（忽略未完成的会话），默认为 false

    String llmProvider,     // LLM提供商

    @NotBlank(message = "面试主题不能为空")
    String skillId,         // 面试主题 ID（如 java-backend, frontend, custom 等）

    String difficulty,      // 难度级别: junior / mid / senior

    List<CategoryDTO> customCategories,   // 自定义面试的分类（JD 解析结果）

    String jdText                          // JD 原文（自定义面试时作为出题依据）
) {}
