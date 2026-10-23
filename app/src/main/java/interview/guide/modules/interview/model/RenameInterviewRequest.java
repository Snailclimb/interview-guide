package interview.guide.modules.interview.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改面试记录名称请求。
 */
public record RenameInterviewRequest(
    @NotBlank(message = "面试记录名称不能为空")
    @Size(max = 120, message = "面试记录名称不能超过120个字符")
    String title
) {}
