package interview.guide.modules.agent.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentLoopPlannerTest {

    @Test
    void limitsPlanToConfiguredNumberOfStepsAndRemovesNumbering() {
        AgentRetryProperties properties = new AgentRetryProperties();
        properties.setLoopMaxSteps(2);
        AgentLoopPlanner planner = new AgentLoopPlanner(
            mock(LlmProviderRegistry.class), mock(PromptSanitizer.class), properties
        );

        assertThat(planner.steps("1. 读取报告\n- 检索资料\n3) 输出建议"))
            .containsExactly("读取报告", "检索资料");
    }

    @Test
    void fallsBackToOneSafeStepForBlankPlan() {
        AgentRetryProperties properties = new AgentRetryProperties();
        AgentLoopPlanner planner = new AgentLoopPlanner(
            mock(LlmProviderRegistry.class), mock(PromptSanitizer.class), properties
        );

        assertThat(planner.steps(" \n "))
            .containsExactly("读取面试报告并确认当前目标");
    }
}
