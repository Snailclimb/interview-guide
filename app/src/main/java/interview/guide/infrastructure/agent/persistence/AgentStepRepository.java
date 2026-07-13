package interview.guide.infrastructure.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentStepRepository extends JpaRepository<AgentStepEntity, String> {

  Optional<AgentStepEntity> findTopByRunIdOrderByStepSequenceDesc(String runId);

  List<AgentStepEntity> findByRunIdAndStepSequenceGreaterThanOrderByStepSequenceAsc(
      String runId,
      Long stepSequence
  );
}
