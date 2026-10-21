package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentChatSessionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentChatSessionRepository extends JpaRepository<AgentChatSessionEntity, Long> {

    Optional<AgentChatSessionEntity> findByInterviewSessionId(String interviewSessionId);
}
