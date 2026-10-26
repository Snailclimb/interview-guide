package interview.guide.modules.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
    @NotBlank(message = "令牌不能为空") String token,
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 128, message = "密码长度必须为 8-128 个字符") String newPassword
) {
}
