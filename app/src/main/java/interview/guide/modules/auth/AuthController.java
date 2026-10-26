package interview.guide.modules.auth;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.model.AuthStateResponse;
import interview.guide.modules.auth.model.AuthUserResponse;
import interview.guide.modules.auth.model.EmailRequest;
import interview.guide.modules.auth.model.EmailTokenRequest;
import interview.guide.modules.auth.model.LoginRequest;
import interview.guide.modules.auth.model.PasswordResetRequest;
import interview.guide.modules.auth.model.RegisterRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** 登录、注册和当前用户接口。 */
@RestController
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final AuthSessionService sessionService;

    @PostMapping("/api/auth/register")
    public Result<AuthUserResponse> register(
        @Valid @RequestBody RegisterRequest request,
        HttpServletResponse response
    ) {
        AuthUserResponse user = authService.register(request);
        if (authService.shouldStartSession(user)) {
            sessionService.create(user.id(), response);
        }
        return Result.success(user);
    }

    @PostMapping("/api/auth/login")
    public Result<AuthUserResponse> login(
        @Valid @RequestBody LoginRequest request,
        HttpServletResponse response
    ) {
        AuthUserResponse user = AuthUserResponse.from(authService.authenticate(request));
        sessionService.create(user.id(), response);
        return Result.success(user);
    }

    @PostMapping("/api/auth/logout")
    public Result<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessionService.invalidate(request, response);
        return Result.success(null);
    }

    @PostMapping("/api/auth/verify-email")
    public Result<Void> verifyEmail(@Valid @RequestBody EmailTokenRequest request) {
        authService.verifyEmail(request.token());
        return Result.success(null);
    }

    @PostMapping("/api/auth/resend-verification")
    public Result<Void> resendVerification(@Valid @RequestBody EmailRequest request) {
        authService.resendVerification(request.email());
        return Result.success(null);
    }

    @PostMapping("/api/auth/password-reset/request")
    public Result<Void> requestPasswordReset(@Valid @RequestBody EmailRequest request) {
        authService.requestPasswordReset(request.email());
        return Result.success(null);
    }

    @PostMapping("/api/auth/password-reset/confirm")
    public Result<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        authService.resetPassword(request.token(), request.newPassword());
        return Result.success(null);
    }

    @GetMapping("/api/auth/me")
    public Result<AuthStateResponse> me(Authentication authentication) {
        String userId = authentication == null || !authentication.isAuthenticated()
            ? null
            : authentication.getPrincipal() instanceof String principal ? principal : null;
        return Result.success(authService.current(userId));
    }
}
