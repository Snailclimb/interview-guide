package interview.guide.modules.agent.model;

/** Agent 最近一次任务的生命周期状态。 */
public enum AgentRunStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
