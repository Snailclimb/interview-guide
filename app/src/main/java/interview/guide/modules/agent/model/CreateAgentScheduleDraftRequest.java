package interview.guide.modules.agent.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/** Agent 生成复习日程草稿时使用的结构化参数。 */
public record CreateAgentScheduleDraftRequest(
    @NotBlank(message = "公司或面试主题不能为空")
    @Size(max = 255, message = "公司或面试主题不能超过255个字符")
    String companyName,

    @NotBlank(message = "复习岗位不能为空")
    @Size(max = 255, message = "复习岗位不能超过255个字符")
    String position,

    @NotNull(message = "复习时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    LocalDateTime interviewTime,

    @Size(max = 64, message = "日程类型不能超过64个字符")
    String interviewType,

    @Size(max = 1000, message = "会议链接不能超过1000个字符")
    String meetingLink,

    @Min(value = 1, message = "轮次必须大于0")
    @Max(value = 20, message = "轮次不能超过20")
    Integer roundNumber,

    @Size(max = 255, message = "联系人不能超过255个字符")
    String interviewer,

    @Size(max = 2000, message = "日程备注不能超过2000个字符")
    String notes,

    @Size(max = 128, message = "幂等键不能超过128个字符")
    String idempotencyKey
) {
}
