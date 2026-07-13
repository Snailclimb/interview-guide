package interview.guide.infrastructure.agent.persistence;

import interview.guide.common.agent.runtime.AgentType;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ContextConfiguration(classes = AgentRunRepositoryTest.TestApplication.class)
@DisplayName("Agent Run 持久化测试")
class AgentRunRepositoryTest {

  @Autowired
  private AgentRunRepository repository;

  @Test
  @DisplayName("同一幂等键只能持久化一条 Run")
  void enforcesUniqueIdempotencyKey() {
    repository.saveAndFlush(createRun("same-idempotency-key", "session-001"));

    assertThatThrownBy(() -> repository.saveAndFlush(
        createRun("same-idempotency-key", "session-002")
    )).isInstanceOf(DataIntegrityViolationException.class);
  }

  private AgentRunEntity createRun(String idempotencyKey, String businessSessionId) {
    return AgentRunEntity.create(
        AgentType.ADAPTIVE_INTERVIEWER,
        businessSessionId,
        idempotencyKey,
        "fingerprint-" + businessSessionId
    );
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan(basePackageClasses = AgentRunEntity.class)
  @EnableJpaRepositories(basePackageClasses = AgentRunRepository.class)
  static class TestApplication {
  }
}
