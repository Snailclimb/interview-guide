package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 编辑单条 Agent 长期记忆的请求。分类和来源由服务端保留，避免前端伪造记忆来源。 */
public record UpdateAgentMemoryItemRequest(
    @NotBlank(message = "记忆内容不能为空")
    @Size(max = 160, message = "记忆内容不能超过 160 个字符")
    String content,
    @Size(max = 64, message = "适用方向不能超过 64 个字符")
    String jobScope
) {
}
