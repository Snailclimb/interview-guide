package interview.guide.modules.review.model;

import jakarta.validation.constraints.NotNull;

/**
 * 触发 AI 分析请求
 *
 * @param type 指定要运行的分析类型
 */
public record AnalyzeRequest(
    @NotNull(message = "分析类型不能为空")
    ArtifactType type
) {}
