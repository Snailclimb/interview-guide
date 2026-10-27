package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 用户在确认前编辑 Agent 动作草稿。 */
public record UpdateAgentActionDraftRequest(
    @NotBlank(message = "草稿内容不能为空")
    @Size(max = 12000, message = "草稿不能超过12000个字符")
    String content
) {
}
