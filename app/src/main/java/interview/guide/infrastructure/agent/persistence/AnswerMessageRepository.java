package interview.guide.infrastructure.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AnswerMessageRepository extends JpaRepository<AnswerMessageEntity, String> {

  Optional<AnswerMessageEntity> findByRunIdAndMessageId(String runId, String messageId);
}
