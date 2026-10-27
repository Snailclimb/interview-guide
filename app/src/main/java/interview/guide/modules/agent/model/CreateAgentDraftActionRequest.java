package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 从 Agent 回答生成草稿动作的请求。 */
public record CreateAgentDraftActionRequest(
    @NotBlank(message = "草稿内容不能为空")
    @Size(max = 12000, message = "草稿不能超过12000个字符")
    String content,
    @Size(max = 128, message = "幂等键不能超过128个字符")
    String idempotencyKey
) {
}
