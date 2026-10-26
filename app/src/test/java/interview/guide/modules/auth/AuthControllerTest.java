package interview.guide.modules.auth;

import interview.guide.modules.auth.model.AuthUserResponse;
import interview.guide.modules.auth.model.RegisterRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {
    @Mock
    private AuthService authService;

    @Mock
    private AuthSessionService sessionService;

    @Mock
    private HttpServletResponse response;

    @Test
    void registrationWithoutEmailVerificationDoesNotCreateSession() {
        AuthUserResponse user = new AuthUserResponse(
            "user-1", "user@example.com", "小明", "ACTIVE", false, null
        );
        when(authService.register(any(RegisterRequest.class))).thenReturn(user);
        when(authService.shouldStartSession(user)).thenReturn(false);

        new AuthController(authService, sessionService)
            .register(new RegisterRequest("user@example.com", "StrongPass123", "小明"), response);

        verify(sessionService, never()).create(any(), any());
    }

    @Test
    void registrationWithVerifiedAccountCreatesSession() {
        AuthUserResponse user = new AuthUserResponse(
            "user-1", "user@example.com", "小明", "ACTIVE", true, null
        );
        when(authService.register(any(RegisterRequest.class))).thenReturn(user);
        when(authService.shouldStartSession(user)).thenReturn(true);

        new AuthController(authService, sessionService)
            .register(new RegisterRequest("user@example.com", "StrongPass123", "小明"), response);

        verify(sessionService).create("user-1", response);
    }
}
