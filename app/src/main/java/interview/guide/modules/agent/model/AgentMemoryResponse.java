package interview.guide.modules.agent.model;

import java.time.LocalDateTime;
import java.util.List;

/** 对前端公开的长期记忆摘要，不返回内部 memoryKey。 */
public record AgentMemoryResponse(
    String lastSkillId,
    List<String> weaknesses,
    List<String> recentGoals,
    LocalDateTime updatedAt
) {
    public AgentMemoryResponse {
        weaknesses = weaknesses == null ? List.of() : List.copyOf(weaknesses);
        recentGoals = recentGoals == null ? List.of() : List.copyOf(recentGoals);
    }

    public static AgentMemoryResponse from(AgentMemorySnapshot snapshot) {
        if (snapshot == null) {
            return new AgentMemoryResponse(null, List.of(), List.of(), null);
        }
        return new AgentMemoryResponse(
            snapshot.lastSkillId(), snapshot.weaknesses(), snapshot.recentGoals(), snapshot.updatedAt()
        );
    }
}
