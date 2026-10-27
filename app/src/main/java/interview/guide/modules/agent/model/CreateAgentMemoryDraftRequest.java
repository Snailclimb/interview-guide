package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 生成一条待确认的结构化长期记忆草稿。 */
public record CreateAgentMemoryDraftRequest(
    @NotBlank(message = "记忆内容不能为空")
    @Size(max = 160, message = "记忆内容不能超过160个字符")
    String content,
    @NotBlank(message = "记忆分类不能为空")
    @Pattern(regexp = "weakness|goal", message = "记忆分类不合法")
    String category,
    @Size(max = 64, message = "适用方向不能超过64个字符")
    String jobScope,
    @Size(max = 128, message = "幂等键不能超过128个字符")
    String idempotencyKey
) {
}
