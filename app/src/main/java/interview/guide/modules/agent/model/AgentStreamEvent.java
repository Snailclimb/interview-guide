package interview.guide.modules.agent.model;

import java.util.List;

/**
 * 面试教练 Agent 的 SSE 事件。
 */
public record AgentStreamEvent(
    String type,
    String step,
    String status,
    String content,
    String message,
    List<AgentSource> sources,
    Integer iteration,
    Integer attempts,
    Long durationMillis,
    String failureReason
) {
    public static AgentStreamEvent step(String step, String status) {
        return base("step", step, status, null, null, List.of());
    }

    public static AgentStreamEvent waiting(String step, String message) {
        return base("step", step, "waiting", null, message, List.of());
    }

    public static AgentStreamEvent delta(String content) {
        return base("delta", null, null, content, null, List.of());
    }

    public static AgentStreamEvent plan(String content) {
        return base("plan", "loop", "completed", content, null, List.of());
    }

    public static AgentStreamEvent done() {
        return base("done", null, null, null, null, List.of());
    }

    public static AgentStreamEvent error(String message) {
        return base("error", null, null, null, message, List.of());
    }

    public static AgentStreamEvent sources(List<AgentSource> sources) {
        return base("sources", null, null, null, null, sources);
    }

    public static AgentStreamEvent loop(int iteration, String status, String observation) {
        return loop(iteration, status, observation, null, 0, 0, null);
    }

    public static AgentStreamEvent loop(
        int iteration, String status, String observation, String action,
        int attempts, long durationMillis, String failureReason
    ) {
        return new AgentStreamEvent(
            "loop", "loop-" + iteration, status,
            action, observation, List.of(), iteration, attempts, durationMillis, failureReason
        );
    }

    private static AgentStreamEvent base(
        String type, String step, String status, String content,
        String message, List<AgentSource> sources
    ) {
        return new AgentStreamEvent(
            type, step, status, content, message,
            sources == null ? List.of() : List.copyOf(sources),
            null, null, null, null
        );
    }
}
