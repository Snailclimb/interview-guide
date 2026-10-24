package interview.guide.modules.agent.model;

import jakarta.validation.constraints.Size;

/** 创建 Agent 对话的可选标题。 */
public record CreateAgentConversationRequest(
    @Size(max = 120, message = "Agent 会话名称不能超过120个字符")
    String title
) {
}
