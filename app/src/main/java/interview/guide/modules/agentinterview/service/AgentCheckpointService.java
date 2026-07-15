package interview.guide.modules.agentinterview.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.infrastructure.agent.persistence.AgentCheckpointEntity;
import interview.guide.infrastructure.agent.persistence.AgentCheckpointRepository;
import interview.guide.infrastructure.agent.persistence.AgentCheckpointState;
import interview.guide.infrastructure.agent.persistence.AgentStepEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentCheckpointService {

  private final AgentCheckpointRepository agentCheckpointRepository;
  private final ObjectMapper objectMapper;

  public void replaceCurrent(AgentStepEntity step) {
    AgentCheckpointState state = AgentCheckpointState.from(step);
    String recoveryState = serialize(state);
    AgentCheckpointEntity checkpoint = agentCheckpointRepository.findByRunId(step.getRunId())
        .orElseGet(() -> AgentCheckpointEntity.create(
            step.getRunId(),
            step.getStepSequence(),
            recoveryState
        ));
    checkpoint.replace(step.getStepSequence(), recoveryState);
    agentCheckpointRepository.save(checkpoint);
  }

  private String serialize(AgentCheckpointState state) {
    try {
      return objectMapper.writeValueAsString(state);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("无法序列化 Agent Checkpoint 恢复状态", exception);
    }
  }
}
