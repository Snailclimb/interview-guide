package interview.guide.infrastructure.agent.persistence;

import interview.guide.common.agent.runtime.AgentType;
import interview.guide.common.agent.runtime.AgentRunStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true"
})
@ContextConfiguration(classes = AgentRunRepositoryTest.TestApplication.class)
@DisplayName("Agent Run 持久化测试")
class AgentRunRepositoryTest {

  @Autowired
  private AgentRunRepository repository;

  @Autowired
  private AgentStepRepository stepRepository;

  @Autowired
  private AnswerMessageRepository answerMessageRepository;

  @Autowired
  private Flyway flyway;

  @Test
  @DisplayName("同一幂等键只能持久化一条 Run")
  void enforcesUniqueIdempotencyKey() {
    repository.saveAndFlush(createRun("same-idempotency-key", "session-001"));

    assertThatThrownBy(() -> repository.saveAndFlush(
        createRun("same-idempotency-key", "session-002")
    )).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("重复执行版本化迁移不会重放且保留已有 Run")
  void repeatedMigrationPreservesExistingRun() {
    AgentRunEntity existingRun = repository.saveAndFlush(
        createRun("preserved-idempotency-key", "session-preserved")
    );
    int appliedMigrations = flyway.info().applied().length;

    var repeatedMigration = flyway.migrate();

    assertThat(repeatedMigration.migrationsExecuted).isZero();
    assertThat(flyway.info().applied()).hasSize(appliedMigrations);
    assertThat(repository.findById(existingRun.getRunId())).contains(existingRun);
  }

  @Test
  @DisplayName("同一 Run 的 Step 序号只能持久化一次")
  void enforcesUniqueStepSequenceWithinRun() {
    AgentRunEntity run = repository.saveAndFlush(
        createRun("step-sequence-key", "session-with-steps")
    );
    stepRepository.saveAndFlush(AgentStepEntity.statusChanged(
        run.getRunId(),
        1,
        AgentRunStatus.CREATED,
        AgentRunStatus.PAUSED
    ));

    assertThatThrownBy(() -> stepRepository.saveAndFlush(AgentStepEntity.statusChanged(
        run.getRunId(),
        1,
        AgentRunStatus.PAUSED,
        AgentRunStatus.CANCELLED
    ))).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("同一 Run 内的回答消息幂等键只能持久化一次")
  void enforcesUniqueAnswerMessageIdempotencyKeyWithinRun() {
    AgentRunEntity run = repository.saveAndFlush(
        createRun("answer-message-unique-key", "session-with-answer-message")
    );
    answerMessageRepository.saveAndFlush(createAnswerMessage(run.getRunId(), "message-001"));

    assertThatThrownBy(() -> answerMessageRepository.saveAndFlush(
        createAnswerMessage(run.getRunId(), "message-001")
    )).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("回答消息必须关联已存在的 Run")
  void enforcesAnswerMessageRunForeignKey() {
    assertThatThrownBy(() -> answerMessageRepository.saveAndFlush(
        createAnswerMessage("missing-run-id", "message-for-missing-run")
    )).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("等待用户回答的 Run 必须保存当前问题标识")
  void enforcesCurrentQuestionForWaitingUserRun() {
    AgentRunEntity run = createRun("waiting-run-check-key", "waiting-run-check-session");
    ReflectionTestUtils.setField(run, "status", AgentRunStatus.WAITING_USER);

    assertThatThrownBy(() -> repository.saveAndFlush(run))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("非等待用户的 Run 不得保留当前问题标识")
  void rejectsCurrentQuestionOutsideWaitingUserRun() {
    AgentRunEntity run = createRun("running-run-check-key", "running-run-check-session");
    ReflectionTestUtils.setField(run, "status", AgentRunStatus.RUNNING);
    ReflectionTestUtils.setField(run, "currentQuestionId", "question-001");

    assertThatThrownBy(() -> repository.saveAndFlush(run))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("取消等待用户的 Run 时会清空当前问题标识")
  void clearsCurrentQuestionWhenCancellingWaitingUserRun() {
    AgentRunEntity run = createWaitingRun(
        "cancel-waiting-key",
        "cancel-waiting-session",
        "question-001"
    );

    assertThat(run.cancel()).isTrue();
    AgentRunEntity cancelledRun = repository.saveAndFlush(run);

    assertThat(cancelledRun.getStatus()).isEqualTo(AgentRunStatus.CANCELLED);
    assertThat(cancelledRun.getCurrentQuestionId()).isNull();
  }

  @Test
  @DisplayName("条件推进等待中的 Run 只允许首个调用成功")
  void advancesWaitingUserRunOnlyOnceAndClearsCurrentQuestion() {
    AgentRunEntity waitingRun = createWaitingRun(
        "advance-waiting-key",
        "advance-waiting-session",
        "question-001"
    );
    AgentRunEntity savedRun = repository.saveAndFlush(waitingRun);
    LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 15, 21, 0);

    int firstUpdatedRows = repository.advanceWaitingUserToRunning(
        savedRun.getRunId(),
        "question-001",
        updatedAt
    );
    int secondUpdatedRows = repository.advanceWaitingUserToRunning(
        savedRun.getRunId(),
        "question-001",
        updatedAt.plusSeconds(1)
    );

    AgentRunEntity advancedRun = repository.findById(savedRun.getRunId()).orElseThrow();
    assertThat(firstUpdatedRows).isOne();
    assertThat(secondUpdatedRows).isZero();
    assertThat(advancedRun.getStatus()).isEqualTo(AgentRunStatus.RUNNING);
    assertThat(advancedRun.getCurrentQuestionId()).isNull();
  }

  private AgentRunEntity createRun(String idempotencyKey, String businessSessionId) {
    return AgentRunEntity.create(
        AgentType.ADAPTIVE_INTERVIEWER,
        businessSessionId,
        idempotencyKey,
        "fingerprint-" + businessSessionId
    );
  }

  private AgentRunEntity createWaitingRun(
      String idempotencyKey,
      String businessSessionId,
      String currentQuestionId) {
    AgentRunEntity run = createRun(idempotencyKey, businessSessionId);
    ReflectionTestUtils.setField(run, "status", AgentRunStatus.WAITING_USER);
    ReflectionTestUtils.setField(run, "currentQuestionId", currentQuestionId);
    return run;
  }

  private AnswerMessageEntity createAnswerMessage(String runId, String messageId) {
    return AnswerMessageEntity.create(
        runId,
        messageId,
        "question-001",
        "候选人回答",
        "payload-fingerprint-" + messageId
    );
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = AgentRunEntity.class)
  @EnableJpaRepositories(basePackageClasses = AgentRunRepository.class)
  static class TestApplication {
  }
}
