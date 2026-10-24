package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/** Agent 对话列表项；与具体消息分离，便于同一份面试报告创建多个线程。 */
public record AgentConversationSummary(
    String agentSessionId,
    String interviewSessionId,
    String title,
    AgentRunStatus runStatus,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
