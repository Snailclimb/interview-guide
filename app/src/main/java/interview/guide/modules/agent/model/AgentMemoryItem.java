package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/**
 * 可由用户管理的单条长期记忆。
 *
 * <p>只保存结构化摘要，不保存原始面试问答；source 和 jobScope 用于说明记忆从哪里来、
 * 适用于哪个面试方向。</p>
 */
public record AgentMemoryItem(
    String id,
    String category,
    String content,
    String source,
    String jobScope,
    Double confidence,
    LocalDateTime updatedAt
) {
    public AgentMemoryItem(
        String id,
        String category,
        String content,
        String source,
        String jobScope,
        LocalDateTime updatedAt
    ) {
        this(id, category, content, source, jobScope, 0.7d, updatedAt);
    }
}
