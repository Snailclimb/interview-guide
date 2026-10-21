package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentChatMessageEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentChatMessageRepository extends JpaRepository<AgentChatMessageEntity, Long> {

    List<AgentChatMessageEntity> findBySessionIdAndCompletedTrueOrderBySequenceNumAsc(Long sessionId);

    Optional<AgentChatMessageEntity> findTopBySessionIdOrderBySequenceNumDesc(Long sessionId);
}
