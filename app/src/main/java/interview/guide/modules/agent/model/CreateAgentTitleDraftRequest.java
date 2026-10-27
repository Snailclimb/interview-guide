package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 生成待确认的面试记录名称草稿。 */
public record CreateAgentTitleDraftRequest(
    @NotBlank(message = "面试记录名称不能为空")
    @Size(max = 120, message = "面试记录名称不能超过120个字符")
    String content,
    @Size(max = 128, message = "幂等键不能超过128个字符")
    String idempotencyKey
) {
}
