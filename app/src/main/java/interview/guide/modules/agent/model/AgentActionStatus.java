package interview.guide.modules.agent.model;

/** Agent 动作状态机；当前第一批动作只落在草稿区。 */
public enum AgentActionStatus {
    PROPOSED,
    CONFIRMED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
