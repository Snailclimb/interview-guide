package interview.guide.modules.review.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 手动编辑分析成果内容请求
 *
 * @param content 编辑后的 Markdown 内容
 */
public record UpdateArtifactRequest(
    @NotBlank(message = "内容不能为空")
    String content
) {}
