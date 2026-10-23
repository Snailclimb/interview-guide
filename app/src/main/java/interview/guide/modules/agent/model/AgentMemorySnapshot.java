package interview.guide.modules.agent.model;

import java.time.LocalDateTime;
import java.util.List;

/** Agent 在跨面试会话中可复用的有限记忆。当前报告始终优先于记忆。 */
public record AgentMemorySnapshot(
    String memoryKey,
    String lastSkillId,
    List<String> weaknesses,
    List<String> recentGoals,
    LocalDateTime updatedAt
) {
    public AgentMemorySnapshot {
        weaknesses = weaknesses == null ? List.of() : List.copyOf(weaknesses);
        recentGoals = recentGoals == null ? List.of() : List.copyOf(recentGoals);
    }

    public static AgentMemorySnapshot empty(String memoryKey) {
        return new AgentMemorySnapshot(memoryKey, null, List.of(), List.of(), null);
    }

    public String toPrompt() {
        if (lastSkillId == null && weaknesses.isEmpty() && recentGoals.isEmpty()) {
            return "暂无长期记忆";
        }
        StringBuilder value = new StringBuilder();
        value.append("仅供参考的长期记忆（当前面试报告优先）：\n");
        if (lastSkillId != null && !lastSkillId.isBlank()) {
            value.append("最近方向：").append(lastSkillId).append('\n');
        }
        if (!weaknesses.isEmpty()) {
            value.append("反复出现的薄弱点：\n");
            weaknesses.forEach(item -> value.append("- ").append(item).append('\n'));
        }
        if (!recentGoals.isEmpty()) {
            value.append("最近训练目标：\n");
            recentGoals.forEach(item -> value.append("- ").append(item).append('\n'));
        }
        value.append("如果长期记忆与本场报告冲突，以本场报告为准。\n");
        return value.toString();
    }
}
