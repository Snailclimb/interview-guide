package interview.guide.modules.agent.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 面试教练 Agent 消息请求。
 */
public record AgentMessageRequest(
    @NotBlank
    @Size(max = 32)
    String goal,
    @NotBlank
    @Size(max = 4000)
    String message,
    @Size(max = 32, message = "历史消息最多保留 32 条")
    List<@Valid AgentHistoryMessage> history,
    @Size(max = 64)
    String providerId
) {
}
