package interview.guide.modules.jdmatch.model;

/**
 * 创建 JD 匹配分析请求
 */
public record JdMatchRequest(
    // 要匹配的简历ID（必填）
    Long resumeId,

    // JD 标题 / 岗位名称（可选）
    String jdTitle,

    // JD 文本内容（必填）
    String jdText
) {
}
