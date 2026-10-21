package interview.guide.modules.agent.model;

/**
 * Agent 回答使用的外部上下文来源摘要。
 */
public record AgentSource(
    String kind,
    String title,
    Double score,
    String reference
) {
}
