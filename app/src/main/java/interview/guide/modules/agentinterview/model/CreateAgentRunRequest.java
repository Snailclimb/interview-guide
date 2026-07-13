package interview.guide.modules.agentinterview.model;

import jakarta.validation.constraints.NotBlank;

public record CreateAgentRunRequest(
    @NotBlank(message = "Agent 类型不能为空") String agentType,
    @NotBlank(message = "业务会话 ID 不能为空") String businessSessionId
) {
}
