package interview.guide.modules.auth.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 255, message = "邮箱长度不能超过 255 个字符")
    String email,

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 128, message = "密码长度必须为 8-128 个字符")
    String password,

    @Size(max = 80, message = "昵称长度不能超过 80 个字符")
    String nickname
) {
}
