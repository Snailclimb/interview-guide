package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentUserMemoryEntity;
import interview.guide.modules.agent.repository.AgentUserMemoryRepository;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 轻量长期记忆服务。
 *
 * <p>记忆只保存 Skill、薄弱点和训练目标等结构化摘要，不保存原始问答，
 * 既能跨面试复用，也避免把隐私对话无限累积。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryService {
    private static final String ANONYMOUS_KEY = "anonymous";
    private static final int MAX_WEAKNESSES = 8;
    private static final int MAX_GOALS = 5;
    private static final int MAX_ITEM_CHARS = 160;

    private final AgentUserMemoryRepository repository;
    private final ObjectMapper objectMapper;

    public String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            return ANONYMOUS_KEY;
        }
        String normalized = value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (normalized.isBlank()) {
            return ANONYMOUS_KEY;
        }
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    public AgentMemorySnapshot load(String memoryKey) {
        String key = normalizeKey(memoryKey);
        return repository.findByMemoryKey(key)
            .map(this::deserialize)
            .orElseGet(() -> AgentMemorySnapshot.empty(key));
    }

    @Transactional
    public AgentMemorySnapshot remember(
        String memoryKey,
        String skillId,
        List<String> weaknesses,
        String goal
    ) {
        String key = normalizeKey(memoryKey);
        AgentMemorySnapshot previous = load(key);
        String nextSkill = clean(skillId, 64);
        if (nextSkill == null) {
            nextSkill = previous.lastSkillId();
        }
        List<String> mergedWeaknesses = merge(previous.weaknesses(), weaknesses, MAX_WEAKNESSES);
        List<String> mergedGoals = merge(previous.recentGoals(), List.of(goal), MAX_GOALS);
        AgentMemorySnapshot next = new AgentMemorySnapshot(
            key, nextSkill, mergedWeaknesses, mergedGoals, LocalDateTime.now()
        );
        try {
            AgentUserMemoryEntity entity = repository.findByMemoryKey(key)
                .orElseGet(() -> AgentUserMemoryEntity.builder().memoryKey(key).build());
            entity.setMemoryJson(objectMapper.writeValueAsString(new MemoryPayload(
                next.lastSkillId(), next.weaknesses(), next.recentGoals()
            )));
            repository.save(entity);
        } catch (RuntimeException error) {
            log.warn("Agent 长期记忆保存失败: memoryKey={}", key, error);
        }
        return next;
    }

    @Transactional
    public void clear(String memoryKey) {
        repository.deleteByMemoryKey(normalizeKey(memoryKey));
    }

    private AgentMemorySnapshot deserialize(AgentUserMemoryEntity entity) {
        try {
            MemoryPayload payload = objectMapper.readValue(entity.getMemoryJson(), MemoryPayload.class);
            return new AgentMemorySnapshot(
                entity.getMemoryKey(), payload.lastSkillId(),
                cleanList(payload.weaknesses(), MAX_WEAKNESSES),
                cleanList(payload.recentGoals(), MAX_GOALS), entity.getUpdatedAt()
            );
        } catch (RuntimeException error) {
            log.warn("Agent 长期记忆读取失败: memoryKey={}", entity.getMemoryKey(), error);
            return AgentMemorySnapshot.empty(entity.getMemoryKey());
        }
    }

    private List<String> merge(List<String> oldValues, List<String> newValues, int max) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (newValues != null) {
            newValues.forEach(value -> addIfPresent(values, value));
        }
        if (oldValues != null) {
            oldValues.forEach(value -> addIfPresent(values, value));
        }
        return values.stream().limit(max).toList();
    }

    private void addIfPresent(LinkedHashSet<String> values, String value) {
        String clean = clean(value, MAX_ITEM_CHARS);
        if (clean != null) {
            values.add(clean);
        }
    }

    private List<String> cleanList(List<String> values, int max) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            values.forEach(value -> {
                String clean = clean(value, MAX_ITEM_CHARS);
                if (clean != null && !result.contains(clean) && result.size() < max) {
                    result.add(clean);
                }
            });
        }
        return List.copyOf(result);
    }

    private String clean(String value, int max) {
        if (value == null) {
            return null;
        }
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (clean.isBlank()) {
            return null;
        }
        return clean.substring(0, Math.min(max, clean.length()));
    }

    private record MemoryPayload(String lastSkillId, List<String> weaknesses, List<String> recentGoals) {
    }
}
