package interview.guide.modules.agent.model;

/**
 * 面试教练 Agent 的 SSE 事件。
 */
public record AgentStreamEvent(
    String type,
    String step,
    String status,
    String content,
    String message
) {
    public static AgentStreamEvent step(String step, String status) {
        return new AgentStreamEvent("step", step, status, null, null);
    }

    public static AgentStreamEvent delta(String content) {
        return new AgentStreamEvent("delta", null, null, content, null);
    }

    public static AgentStreamEvent done() {
        return new AgentStreamEvent("done", null, null, null, null);
    }

    public static AgentStreamEvent error(String message) {
        return new AgentStreamEvent("error", null, null, null, message);
    }
}
