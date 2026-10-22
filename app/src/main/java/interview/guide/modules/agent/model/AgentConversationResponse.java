package interview.guide.modules.agent.model;

import java.util.List;

/** Agent 会话恢复接口返回的数据。 */
public record AgentConversationResponse(
    String interviewSessionId,
    String goal,
    String providerId,
    String runId,
    AgentRunStatus runStatus,
    java.time.LocalDateTime runStartedAt,
    java.time.LocalDateTime runFinishedAt,
    String runError,
    String runStep,
    String runStepStatus,
    String runNotice,
    List<AgentSource> runSources,
    List<AgentHistoryMessage> messages
) {
    public AgentConversationResponse {
        runSources = runSources == null ? List.of() : List.copyOf(runSources);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
