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
    String providerId,
    @Size(max = 64)
    String memoryKey,
    @Size(max = 36)
    String agentSessionId
) {
    /** 兼容已有调用方；长期记忆是新增的可选能力。 */
    public AgentMessageRequest(
        String goal,
        String message,
        List<AgentHistoryMessage> history,
        String providerId
    ) {
        this(goal, message, history, providerId, null, null);
    }

    public AgentMessageRequest(
        String goal,
        String message,
        List<AgentHistoryMessage> history,
        String providerId,
        String memoryKey
    ) {
        this(goal, message, history, providerId, memoryKey, null);
    }

    public AgentMessageRequest withMemoryKey(String nextMemoryKey) {
        return new AgentMessageRequest(goal, message, history, providerId, nextMemoryKey, agentSessionId);
    }

    /**
     * 固化本次运行解析出的首选 Provider，保证应用重启恢复时沿用同一候选链路。
     */
    public AgentMessageRequest withProviderId(String nextProviderId) {
        return new AgentMessageRequest(goal, message, history, nextProviderId, memoryKey, agentSessionId);
    }
}
