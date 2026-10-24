package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentSource;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopEngineTest {

    @Test
    void executesEveryBoundedStepAndCarriesObservations() {
        AgentContextService.AgentContext context = new AgentContextService.AgentContext(
            "report", "java-backend", "skill", "rag",
            "query", List.of(new AgentSource("skill", "Java Skill", null, "S1")),
            "memory", List.of("Redis")
        );

        AgentLoopEngine.LoopResult result = new AgentLoopEngine().execute(
            context, List.of("读取报告", "核对 Skill", "给出建议")
        );

        assertThat(result.iterations()).hasSize(3);
        assertThat(result.iterations().get(0).observation()).contains("1 个待关注项");
        assertThat(result.iterations().get(1).observation()).contains("1 个可引用来源");
        assertThat(result.trace()).contains("第 3 步：给出建议");
    }

    @Test
    void explicitlyMarksMissingEvidenceWithoutInventingSources() {
        AgentContextService.AgentContext context = new AgentContextService.AgentContext(
            "report", "java-backend", "skill", "rag", "query", List.of(), "memory", List.of()
        );

        AgentLoopEngine.LoopResult result = new AgentLoopEngine().execute(
            context, List.of("读取报告", "核对资料")
        );

        assertThat(result.iterations().get(1).observation()).contains("不得声称使用了外部依据");
    }
}
