package interview.guide.infrastructure.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Immutable
@Table(
    name = "agent_answer_messages",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_answer_messages_run_message",
        columnNames = {"run_id", "message_id"}
    )
)
public class AnswerMessageEntity {

  @Id
  @Column(name = "answer_message_id", nullable = false, updatable = false, length = 36)
  private String answerMessageId;

  @Column(name = "run_id", nullable = false, updatable = false, length = 36)
  private String runId;

  @Column(name = "message_id", nullable = false, updatable = false, length = 64)
  private String messageId;

  @Column(name = "answered_question_id", nullable = false, updatable = false, length = 64)
  private String answeredQuestionId;

  @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
  private String content;

  @Column(name = "payload_fingerprint", nullable = false, updatable = false, length = 64)
  private String payloadFingerprint;

  @Column(name = "received_at", nullable = false, updatable = false)
  private LocalDateTime receivedAt;

  protected AnswerMessageEntity() {
  }

  public static AnswerMessageEntity create(
      String runId,
      String messageId,
      String answeredQuestionId,
      String content,
      String payloadFingerprint) {
    AnswerMessageEntity entity = new AnswerMessageEntity();
    entity.answerMessageId = UUID.randomUUID().toString();
    entity.runId = runId;
    entity.messageId = messageId;
    entity.answeredQuestionId = answeredQuestionId;
    entity.content = content;
    entity.payloadFingerprint = payloadFingerprint;
    entity.receivedAt = LocalDateTime.now();
    return entity;
  }

  public String getAnswerMessageId() {
    return answerMessageId;
  }

  public String getRunId() {
    return runId;
  }

  public String getMessageId() {
    return messageId;
  }

  public String getAnsweredQuestionId() {
    return answeredQuestionId;
  }

  public String getContent() {
    return content;
  }

  public String getPayloadFingerprint() {
    return payloadFingerprint;
  }

  public LocalDateTime getReceivedAt() {
    return receivedAt;
  }
}
