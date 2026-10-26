package interview.guide.modules.auth.model;

import jakarta.validation.constraints.NotBlank;

public record EmailTokenRequest(
    @NotBlank(message = "令牌不能为空") String token
) {
}
