package interview.guide.infrastructure.agent.persistence;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface AgentRunRepository extends JpaRepository<AgentRunEntity, String> {

  Optional<AgentRunEntity> findByIdempotencyKey(String idempotencyKey);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(value = """
      update agent_runs
      set status = 'RUNNING',
          current_question_id = null,
          updated_at = :updatedAt
      where run_id = :runId
        and status = 'WAITING_USER'
        and current_question_id = :currentQuestionId
      """, nativeQuery = true)
  int advanceWaitingUserToRunning(
      @Param("runId") String runId,
      @Param("currentQuestionId") String currentQuestionId,
      @Param("updatedAt") LocalDateTime updatedAt
  );
}
