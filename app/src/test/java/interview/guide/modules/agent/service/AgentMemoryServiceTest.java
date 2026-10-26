package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentUserMemoryEntity;
import interview.guide.modules.agent.model.AgentMemoryItem;
import interview.guide.modules.agent.repository.AgentUserMemoryRepository;
import java.util.List;
import java.time.LocalDateTime;
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

    @Test
    void editsAndDeletesOneMemoryItemWithoutChangingOtherItems() throws Exception {
        AgentUserMemoryRepository repository = mock(AgentUserMemoryRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentUserMemoryEntity entity = AgentUserMemoryEntity.builder()
            .memoryKey("browser-key")
            .memoryJson(objectMapper.writeValueAsString(java.util.Map.of(
                "lastSkillId", "java-backend",
                "weaknesses", List.of("Redis 缓存"),
                "recentGoals", List.of("practice"),
                "items", List.of(
                    new AgentMemoryItem("w-1", "weakness", "Redis 缓存", "interview-report", "java-backend", null),
                    new AgentMemoryItem("g-1", "goal", "practice", "interview-report", "java-backend", null)
                )
            )))
            .build();
        when(repository.findByMemoryKey("browser-key")).thenReturn(Optional.of(entity));
        when(repository.save(any(AgentUserMemoryEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AgentMemoryService service = new AgentMemoryService(repository, objectMapper);
        var updated = service.updateItem("browser-key", "w-1", "Redis 穿透与击穿", "java-backend");
        assertThat(updated.items()).extracting(AgentMemoryItem::content)
            .containsExactly("Redis 穿透与击穿", "practice");
        assertThat(updated.weaknesses()).containsExactly("Redis 穿透与击穿");

        var deleted = service.deleteItem("browser-key", "g-1");
        assertThat(deleted.items()).extracting(AgentMemoryItem::id).containsExactly("w-1");
        assertThat(deleted.recentGoals()).isEmpty();
    }

    @Test
    void filtersMemoryPromptByCurrentJobScopeWhenStructuredItemsExist() {
        var snapshot = new interview.guide.modules.agent.model.AgentMemorySnapshot(
            "browser-key",
            "java-backend",
            List.of("Redis 缓存"),
            List.of("Java 训练"),
            null,
            List.of(
                new AgentMemoryItem("java-1", "weakness", "Redis 缓存", "report", "java-backend", 0.9d, null),
                new AgentMemoryItem("python-1", "weakness", "Python 异步", "report", "python-backend", 0.9d, null),
                new AgentMemoryItem("java-2", "goal", "Java 训练", "report", "java-backend", 0.8d, null)
            )
        );

        assertThat(snapshot.toPrompt("python-backend"))
            .contains("Python 异步")
            .doesNotContain("Redis 缓存")
            .doesNotContain("Java 训练")
            .doesNotContain("java-backend");
    }

    @Test
    void removesExpiredItemsBeforeTheyReachAgentContext() throws Exception {
        AgentUserMemoryRepository repository = mock(AgentUserMemoryRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        LocalDateTime now = LocalDateTime.now();
        AgentUserMemoryEntity entity = AgentUserMemoryEntity.builder()
            .memoryKey("browser-key")
            .updatedAt(now)
            .memoryJson(objectMapper.writeValueAsString(java.util.Map.of(
                "lastSkillId", "java-backend",
                "items", List.of(new AgentMemoryItem(
                    "old-1", "weakness", "过期薄弱点", "report", "java-backend", 0.8d,
                    now.minusDays(200), now.minusDays(1)
                ))
            )))
            .build();
        when(repository.findByMemoryKey("browser-key")).thenReturn(Optional.of(entity));

        AgentMemoryService service = new AgentMemoryService(repository, objectMapper);

        assertThat(service.load("browser-key").items()).isEmpty();
        assertThat(service.load("browser-key").toPrompt("java-backend"))
            .isEqualTo("暂无长期记忆");
    }

    @Test
    void newItemsReceiveFreshExpirationTime() throws Exception {
        AgentUserMemoryRepository repository = mock(AgentUserMemoryRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(repository.findByMemoryKey("browser-key")).thenReturn(Optional.empty());
        when(repository.save(any(AgentUserMemoryEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        AgentMemoryProperties properties = new AgentMemoryProperties();
        properties.setRetentionDays(30);
        AgentMemoryService service = new AgentMemoryService(repository, objectMapper, properties);

        var created = service.remember("browser-key", "java-backend", List.of("Redis"), null);
        assertThat(created.items().get(0).expiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void recordsLowSensitivityAuditForManualMemoryChanges() throws Exception {
        AgentUserMemoryRepository repository = mock(AgentUserMemoryRepository.class);
        AgentAuditService auditService = mock(AgentAuditService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(repository.findByMemoryKey("browser-key")).thenReturn(Optional.empty());
        when(repository.save(any(AgentUserMemoryEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        AgentMemoryService service = new AgentMemoryService(
            repository, objectMapper, new AgentMemoryProperties(), auditService
        );

        service.remember("browser-key", "java-backend", List.of("Redis"), null);

        org.mockito.Mockito.verify(auditService).recordQuietly(
            "browser-key", null, null, null, "MEMORY_REMEMBERED", "memory", "browser-key", "success"
        );
    }
}
