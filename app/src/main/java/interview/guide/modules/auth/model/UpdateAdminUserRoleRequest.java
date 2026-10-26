package interview.guide.modules.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateAdminUserRoleRequest(
    @NotBlank(message = "账号角色不能为空")
    @Pattern(regexp = "USER|ADMIN", message = "账号角色不合法")
    String role
) {
}
