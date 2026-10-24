package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 修改 Agent 会话名称。 */
public record RenameAgentConversationRequest(
    @NotBlank(message = "Agent 会话名称不能为空")
    @Size(max = 120, message = "Agent 会话名称不能超过120个字符")
    String title
) {
}
