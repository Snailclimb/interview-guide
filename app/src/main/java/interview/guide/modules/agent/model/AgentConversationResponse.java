package interview.guide.modules.agent.model;

import java.util.List;

/** Agent 会话恢复接口返回的数据。 */
public record AgentConversationResponse(
    String interviewSessionId,
    String agentSessionId,
    String title,
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
    String runPartialAnswer,
    List<AgentSource> runSources,
    List<AgentHistoryMessage> messages,
    AgentRunUsage runUsage,
    String loopPlan,
    int loopNextStep,
    List<Integer> loopCompletedStepIndices,
    List<AgentLoopTraceItem> loopTrace
) {
    /** 兼容未携带运行用量的旧调用方。 */
    public AgentConversationResponse(
        String interviewSessionId,
        String agentSessionId,
        String title,
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
        String runPartialAnswer,
        List<AgentSource> runSources,
        List<AgentHistoryMessage> messages
    ) {
        this(interviewSessionId, agentSessionId, title, goal, providerId, runId, runStatus,
            runStartedAt, runFinishedAt, runError, runStep, runStepStatus, runNotice,
            runPartialAnswer, runSources, messages, null, null, 0, List.of(), List.of());
    }

    /** 兼容已有服务和测试调用方。 */
    public AgentConversationResponse(
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
        String runPartialAnswer,
        List<AgentSource> runSources,
        List<AgentHistoryMessage> messages
    ) {
        this(interviewSessionId, null, null, goal, providerId, runId, runStatus,
            runStartedAt, runFinishedAt, runError, runStep, runStepStatus, runNotice,
            runPartialAnswer, runSources, messages, null, null, 0, List.of(), List.of());
    }

    public AgentConversationResponse {
        runSources = runSources == null ? List.of() : List.copyOf(runSources);
        messages = messages == null ? List.of() : List.copyOf(messages);
        loopNextStep = Math.max(0, loopNextStep);
        loopCompletedStepIndices = loopCompletedStepIndices == null
            ? List.of() : List.copyOf(loopCompletedStepIndices);
        loopTrace = loopTrace == null ? List.of() : List.copyOf(loopTrace);
    }
}
