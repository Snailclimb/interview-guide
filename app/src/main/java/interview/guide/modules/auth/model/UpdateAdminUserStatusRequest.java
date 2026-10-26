package interview.guide.modules.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateAdminUserStatusRequest(
    @NotBlank(message = "账号状态不能为空")
    @Pattern(regexp = "ACTIVE|DISABLED", message = "账号状态不合法")
    String status
) {
}
