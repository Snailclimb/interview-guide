package interview.guide.modules.agentinterview.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitAnswerMessageRequest(
    @NotBlank(message = "messageId 不能为空")
    @Size(max = 64, message = "messageId 长度不能超过 64")
    String messageId,
    @NotBlank(message = "questionId 不能为空")
    @Size(max = 64, message = "questionId 长度不能超过 64")
    String questionId,
    @NotBlank(message = "content 不能为空")
    @Size(max = 10000, message = "content 长度不能超过 10000")
    String content
) {
}
