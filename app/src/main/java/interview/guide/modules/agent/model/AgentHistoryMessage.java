package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Agent 请求中的短期对话消息。
 */
public record AgentHistoryMessage(
    @NotBlank
    @Pattern(regexp = "user|assistant")
    String role,
    @NotBlank
    String content
) {
}
