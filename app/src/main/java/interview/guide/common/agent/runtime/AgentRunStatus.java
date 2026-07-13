package interview.guide.common.agent.runtime;

public enum AgentRunStatus {
  CREATED,
  RUNNING,
  WAITING_USER,
  WAITING_APPROVAL,
  PAUSED,
  COMPLETED,
  FAILED,
  CANCELLED
}
