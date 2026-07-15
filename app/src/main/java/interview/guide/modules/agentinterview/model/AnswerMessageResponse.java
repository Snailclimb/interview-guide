package interview.guide.modules.agentinterview.model;

import interview.guide.common.agent.runtime.AgentRunStatus;
import interview.guide.infrastructure.agent.persistence.AnswerMessageEntity;

import java.time.LocalDateTime;

public record AnswerMessageResponse(
    String runId,
    String messageId,
    String answeredQuestionId,
    LocalDateTime receivedAt,
    AgentRunStatus acceptedStatus
) {

  public static AnswerMessageResponse accepted(AnswerMessageEntity entity) {
    return new AnswerMessageResponse(
        entity.getRunId(),
        entity.getMessageId(),
        entity.getAnsweredQuestionId(),
        entity.getReceivedAt(),
        AgentRunStatus.RUNNING
    );
  }
}
