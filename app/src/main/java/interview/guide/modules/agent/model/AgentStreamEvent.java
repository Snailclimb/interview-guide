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
    List<AgentSource> sources
) {
    public static AgentStreamEvent step(String step, String status) {
        return new AgentStreamEvent("step", step, status, null, null, List.of());
    }

    public static AgentStreamEvent waiting(String step, String message) {
        return new AgentStreamEvent("step", step, "waiting", null, message, List.of());
    }

    public static AgentStreamEvent delta(String content) {
        return new AgentStreamEvent("delta", null, null, content, null, List.of());
    }

    public static AgentStreamEvent plan(String content) {
        return new AgentStreamEvent("plan", "loop", "completed", content, null, List.of());
    }

    public static AgentStreamEvent done() {
        return new AgentStreamEvent("done", null, null, null, null, List.of());
    }

    public static AgentStreamEvent error(String message) {
        return new AgentStreamEvent("error", null, null, null, message, List.of());
    }

    public static AgentStreamEvent sources(List<AgentSource> sources) {
        return new AgentStreamEvent("sources", null, null, null, null,
            sources == null ? List.of() : List.copyOf(sources));
    }

    public static AgentStreamEvent loop(int iteration, String status, String observation) {
        return new AgentStreamEvent(
            "loop", "loop-" + iteration, status, null, observation, List.of()
        );
    }
}
