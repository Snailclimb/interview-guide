package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 从 Agent 回答生成训练计划草稿的请求。 */
public record CreateAgentTrainingPlanDraftRequest(
    @NotBlank(message = "训练计划草稿内容不能为空")
    @Size(max = 12000, message = "训练计划草稿不能超过12000个字符")
    String content,
    @Size(max = 128, message = "幂等键不能超过128个字符")
    String idempotencyKey
) {
}
