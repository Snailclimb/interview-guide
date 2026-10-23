package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentUserMemoryEntity;
import interview.guide.modules.agent.repository.AgentUserMemoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryServiceTest {

    @Test
    void remembersOnlyBoundedStructuredSummaries() throws Exception {
        AgentUserMemoryRepository repository = mock(AgentUserMemoryRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(repository.findByMemoryKey("browser-key")).thenReturn(Optional.empty());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(repository.save(any(AgentUserMemoryEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AgentMemoryService service = new AgentMemoryService(repository, objectMapper);
        List<String> weaknesses = java.util.stream.IntStream.range(0, 20)
            .mapToObj(index -> "薄弱点-" + index)
            .toList();

        var memory = service.remember("browser-key", "java-backend", weaknesses, "practice");

        assertThat(memory.memoryKey()).isEqualTo("browser-key");
        assertThat(memory.weaknesses()).hasSize(8);
        assertThat(memory.recentGoals()).containsExactly("practice");
        assertThat(memory.toPrompt()).contains("当前面试报告优先");
        verify(repository).save(any(AgentUserMemoryEntity.class));
    }

    @Test
    void normalizesUnsafeOrMissingMemoryKeysAndSupportsClear() {
        AgentUserMemoryRepository repository = mock(AgentUserMemoryRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AgentMemoryService service = new AgentMemoryService(repository, objectMapper);

        assertThat(service.normalizeKey("  user@example.com/abc  ")).isEqualTo("user_example_com_abc");
        assertThat(service.normalizeKey(" ")).isEqualTo("anonymous");

        service.clear(null);
        verify(repository).deleteByMemoryKey("anonymous");
    }
}
