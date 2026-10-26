package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.agent.model.AgentMemoryItem;
import interview.guide.modules.agent.model.AgentUserMemoryEntity;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.repository.AgentUserMemoryRepository;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
public class AgentMemoryService {
    private static final String ANONYMOUS_KEY = "anonymous";
    private static final int MAX_WEAKNESSES = 8;
    private static final int MAX_GOALS = 5;
    private static final int MAX_ITEM_CHARS = 160;
    private static final String CATEGORY_WEAKNESS = "weakness";
    private static final String CATEGORY_GOAL = "goal";
    private static final String DEFAULT_SOURCE = "interview-report";

    private final AgentUserMemoryRepository repository;
    private final ObjectMapper objectMapper;
    private final AgentMemoryProperties properties;

    /** 保留无 Spring 容器调用方式，便于单元测试和兼容旧调用方。 */
    public AgentMemoryService(
        AgentUserMemoryRepository repository,
        ObjectMapper objectMapper
    ) {
        this(repository, objectMapper, new AgentMemoryProperties());
    }

    @Autowired
    public AgentMemoryService(
        AgentUserMemoryRepository repository,
        ObjectMapper objectMapper,
        AgentMemoryProperties properties
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties == null ? new AgentMemoryProperties() : properties;
    }

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
            .map(this::removeExpired)
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
        List<AgentMemoryItem> items = mergeItems(
            previous.items(), weaknesses, CATEGORY_WEAKNESS, DEFAULT_SOURCE, nextSkill, MAX_WEAKNESSES
        );
        items = mergeItems(
            items, goal == null ? List.of() : List.of(goal), CATEGORY_GOAL,
            DEFAULT_SOURCE, nextSkill, MAX_GOALS
        );
        return saveSnapshot(key, nextSkill, items);
    }

    /** 编辑一条结构化记忆，保留其分类和来源，只允许修改内容和适用方向。 */
    @Transactional
    public AgentMemorySnapshot updateItem(
        String memoryKey, String itemId, String content, String jobScope
    ) {
        String key = normalizeKey(memoryKey);
        String normalizedId = clean(itemId, 64);
        String normalizedContent = clean(content, MAX_ITEM_CHARS);
        if (normalizedId == null || normalizedContent == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "记忆标识和内容不能为空");
        }
        AgentMemorySnapshot previous = load(key);
        List<AgentMemoryItem> items = new ArrayList<>(previous.items());
        int index = indexOf(items, normalizedId);
        if (index < 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "长期记忆不存在");
        }
        AgentMemoryItem current = items.get(index);
        String normalizedScope = jobScope == null ? current.jobScope() : clean(jobScope, 64);
        items.set(index, new AgentMemoryItem(
            current.id(), current.category(), normalizedContent, current.source(),
            normalizedScope,
            current.confidence(), LocalDateTime.now(), expiresAt(LocalDateTime.now())
        ));
        return saveSnapshot(key, previous.lastSkillId(), items);
    }

    /** 删除一条结构化记忆；删除后会同步更新兼容旧接口的摘要字段。 */
    @Transactional
    public AgentMemorySnapshot deleteItem(String memoryKey, String itemId) {
        String key = normalizeKey(memoryKey);
        String normalizedId = clean(itemId, 64);
        AgentMemorySnapshot previous = load(key);
        List<AgentMemoryItem> items = new ArrayList<>(previous.items());
        int index = indexOf(items, normalizedId);
        if (index < 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "长期记忆不存在");
        }
        items.remove(index);
        return saveSnapshot(key, previous.lastSkillId(), items);
    }

    @Transactional
    public void clear(String memoryKey) {
        repository.deleteByMemoryKey(normalizeKey(memoryKey));
    }

    private AgentMemorySnapshot deserialize(AgentUserMemoryEntity entity) {
        try {
            MemoryPayload payload = objectMapper.readValue(entity.getMemoryJson(), MemoryPayload.class);
            List<AgentMemoryItem> items = normalizeItems(
                payload == null ? null : payload.items(),
                payload == null ? null : payload.weaknesses(),
                payload == null ? null : payload.recentGoals(),
                entity.getUpdatedAt()
            );
            return new AgentMemorySnapshot(
                entity.getMemoryKey(), payload == null ? null : clean(payload.lastSkillId(), 64),
                valuesForCategory(items, CATEGORY_WEAKNESS, MAX_WEAKNESSES),
                valuesForCategory(items, CATEGORY_GOAL, MAX_GOALS), entity.getUpdatedAt(), items
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

    private List<AgentMemoryItem> mergeItems(
        List<AgentMemoryItem> oldItems,
        List<String> newValues,
        String category,
        String source,
        String jobScope,
        int max
    ) {
        List<AgentMemoryItem> result = new ArrayList<>();
        List<AgentMemoryItem> categoryItems = new ArrayList<>();
        if (oldItems != null) {
            oldItems.stream()
                .filter(item -> item != null)
                .forEach(item -> {
                    if (category.equals(item.category())) {
                        categoryItems.add(item);
                    } else {
                        result.add(item);
                    }
                });
        }
        if (newValues != null) {
            newValues.forEach(value -> {
                String normalized = clean(value, MAX_ITEM_CHARS);
                if (normalized == null || categoryItems.stream().anyMatch(
                    item -> normalized.equals(item.content())
                )) {
                    return;
                }
                categoryItems.add(0, new AgentMemoryItem(
                    UUID.randomUUID().toString(), category, normalized, source,
                    clean(jobScope, 64), 0.7d, LocalDateTime.now(), expiresAt(LocalDateTime.now())
                ));
            });
        }
        result.addAll(categoryItems.stream().limit(max).toList());
        return List.copyOf(result);
    }

    private List<AgentMemoryItem> normalizeItems(
        List<MemoryItemPayload> payloadItems,
        List<String> legacyWeaknesses,
        List<String> legacyGoals,
        LocalDateTime fallbackUpdatedAt
    ) {
        List<AgentMemoryItem> result = new ArrayList<>();
        if (payloadItems != null) {
            int index = 0;
            for (MemoryItemPayload item : payloadItems) {
                if (item == null) {
                    continue;
                }
                String content = clean(item.content(), MAX_ITEM_CHARS);
                String category = normalizeCategory(item.category());
                if (content == null || category == null || result.size() >= MAX_WEAKNESSES + MAX_GOALS) {
                    continue;
                }
                String id = clean(item.id(), 64);
                if (id == null) {
                    id = category + "-" + index;
                }
                result.add(new AgentMemoryItem(
                    id, category, content, clean(item.source(), 64), clean(item.jobScope(), 64),
                    normalizeConfidence(item.confidence()),
                    item.updatedAt() == null ? fallbackUpdatedAt : item.updatedAt(),
                    item.expiresAt() == null
                        ? expiresAt(item.updatedAt() == null ? fallbackUpdatedAt : item.updatedAt())
                        : item.expiresAt()
                ));
                index++;
            }
        }
        if (result.isEmpty()) {
            addLegacyItems(result, legacyWeaknesses, CATEGORY_WEAKNESS, MAX_WEAKNESSES, fallbackUpdatedAt);
            addLegacyItems(result, legacyGoals, CATEGORY_GOAL, MAX_GOALS, fallbackUpdatedAt);
        }
        return List.copyOf(result);
    }

    private void addLegacyItems(
        List<AgentMemoryItem> result,
        List<String> values,
        String category,
        int max,
        LocalDateTime updatedAt
    ) {
        List<String> cleaned = cleanList(values, max);
        for (int index = 0; index < cleaned.size(); index++) {
            result.add(new AgentMemoryItem(
                category + "-" + index, category, cleaned.get(index), "legacy-summary", null,
                0.5d, updatedAt, expiresAt(updatedAt)
            ));
        }
    }

    private List<String> valuesForCategory(List<AgentMemoryItem> items, String category, int max) {
        return items == null ? List.of() : items.stream()
            .filter(item -> item != null && category.equals(item.category()))
            .map(AgentMemoryItem::content)
            .map(value -> clean(value, MAX_ITEM_CHARS))
            .filter(value -> value != null)
            .distinct()
            .limit(max)
            .toList();
    }

    private int indexOf(List<AgentMemoryItem> items, String itemId) {
        if (itemId == null || items == null) {
            return -1;
        }
        for (int index = 0; index < items.size(); index++) {
            if (itemId.equals(items.get(index).id())) {
                return index;
            }
        }
        return -1;
    }

    private AgentMemorySnapshot saveSnapshot(
        String key, String lastSkillId, List<AgentMemoryItem> items
    ) {
        LocalDateTime now = LocalDateTime.now();
        List<AgentMemoryItem> safeItems = items == null ? List.of() : items.stream()
            .filter(item -> item != null && !isExpired(item, now))
            .toList();
        AgentMemorySnapshot next = new AgentMemorySnapshot(
            key,
            clean(lastSkillId, 64),
            valuesForCategory(safeItems, CATEGORY_WEAKNESS, MAX_WEAKNESSES),
            valuesForCategory(safeItems, CATEGORY_GOAL, MAX_GOALS),
            LocalDateTime.now(),
            safeItems
        );
        try {
            AgentUserMemoryEntity entity = repository.findByMemoryKey(key)
                .orElseGet(() -> AgentUserMemoryEntity.builder().memoryKey(key).build());
            entity.setMemoryJson(objectMapper.writeValueAsString(new MemoryPayload(
                next.lastSkillId(), next.weaknesses(), next.recentGoals(),
                next.items().stream().map(MemoryItemPayload::from).toList()
            )));
            repository.save(entity);
        } catch (RuntimeException error) {
            log.warn("Agent 长期记忆保存失败: memoryKey={}", key, error);
        }
        return next;
    }

    private String normalizeCategory(String category) {
        if (CATEGORY_WEAKNESS.equals(category) || CATEGORY_GOAL.equals(category)) {
            return category;
        }
        return null;
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

    private record MemoryPayload(
        String lastSkillId,
        List<String> weaknesses,
        List<String> recentGoals,
        List<MemoryItemPayload> items
    ) {
    }

    private record MemoryItemPayload(
        String id,
        String category,
        String content,
        String source,
        String jobScope,
        Double confidence,
        LocalDateTime updatedAt,
        LocalDateTime expiresAt
    ) {
        private static MemoryItemPayload from(AgentMemoryItem item) {
            return new MemoryItemPayload(
                item.id(), item.category(), item.content(), item.source(), item.jobScope(),
                item.confidence(), item.updatedAt(), item.expiresAt()
            );
        }
    }

    private double normalizeConfidence(Double confidence) {
        if (confidence == null || confidence.isNaN() || confidence.isInfinite()) {
            return 0.5d;
        }
        return Math.max(0d, Math.min(1d, confidence));
    }

    private AgentMemorySnapshot removeExpired(AgentMemorySnapshot snapshot) {
        if (snapshot == null || snapshot.items().isEmpty() || !properties.isExpirationEnabled()) {
            return snapshot;
        }
        LocalDateTime now = LocalDateTime.now();
        List<AgentMemoryItem> activeItems = snapshot.items().stream()
            .filter(item -> !isExpired(item, now))
            .toList();
        if (activeItems.size() == snapshot.items().size()) {
            return snapshot;
        }
        return new AgentMemorySnapshot(
            snapshot.memoryKey(), activeItems.isEmpty() ? null : snapshot.lastSkillId(),
            valuesForCategory(activeItems, CATEGORY_WEAKNESS, MAX_WEAKNESSES),
            valuesForCategory(activeItems, CATEGORY_GOAL, MAX_GOALS),
            snapshot.updatedAt(), activeItems
        );
    }

    private boolean isExpired(AgentMemoryItem item, LocalDateTime now) {
        return properties.isExpirationEnabled()
            && item.expiresAt() != null
            && !item.expiresAt().isAfter(now);
    }

    private LocalDateTime expiresAt(LocalDateTime updatedAt) {
        if (!properties.isExpirationEnabled() || updatedAt == null) {
            return null;
        }
        return updatedAt.plus(properties.getRetentionDays(), ChronoUnit.DAYS);
    }
}
