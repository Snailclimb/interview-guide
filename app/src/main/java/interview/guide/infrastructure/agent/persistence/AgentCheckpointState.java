package interview.guide.infrastructure.agent.persistence;

import interview.guide.common.agent.runtime.AgentRunStatus;

public final class AgentCheckpointState {

  public static final int SCHEMA_VERSION = 1;

  private final int schemaVersion;
  private final long lastAppliedStepSequence;
  private final AgentRunStatus status;
  private final String currentQuestionId;

  private AgentCheckpointState(
      int schemaVersion,
      long lastAppliedStepSequence,
      AgentRunStatus status,
      String currentQuestionId) {
    this.schemaVersion = schemaVersion;
    this.lastAppliedStepSequence = lastAppliedStepSequence;
    this.status = status;
    this.currentQuestionId = currentQuestionId;
  }

  public static AgentCheckpointState from(AgentStepEntity step) {
    return new AgentCheckpointState(
        SCHEMA_VERSION,
        step.getStepSequence(),
        step.getStatus(),
        step.getCurrentQuestionId()
    );
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  public long getLastAppliedStepSequence() {
    return lastAppliedStepSequence;
  }

  public AgentRunStatus getStatus() {
    return status;
  }

  public String getCurrentQuestionId() {
    return currentQuestionId;
  }
}
