package interview.guide.modules.agentinterview.service;

import interview.guide.infrastructure.agent.persistence.AgentRunEntity;
import interview.guide.infrastructure.agent.persistence.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentRunPersistenceService {

  private final AgentRunRepository agentRunRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AgentRunEntity create(AgentRunEntity entity) {
    return agentRunRepository.saveAndFlush(entity);
  }
}
