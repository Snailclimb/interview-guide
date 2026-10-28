package interview.guide.modules.agent.model;

import jakarta.validation.constraints.Size;

/** 创建 Agent 对话的可选标题和幂等请求键。 */
public record CreateAgentConversationRequest(
    @Size(max = 120, message = "Agent 会话名称不能超过120个字符")
    String title,
    @Size(max = 128, message = "Agent 创建请求标识不能超过128个字符")
    String idempotencyKey
) {
}
