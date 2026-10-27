package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotNull;

/** 更新训练任务状态。 */
public record UpdateAgentTrainingTaskRequest(
    @NotNull(message = "训练任务状态不能为空")
    AgentTrainingTaskStatus status
) {
}
