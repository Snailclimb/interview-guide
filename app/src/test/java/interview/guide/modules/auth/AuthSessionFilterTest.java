package interview.guide.modules.auth;

import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.repository.UserAccountRepository;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class AuthSessionFilterTest {
    @Mock
    private AuthSessionService sessionService;

    @Mock
    private UserAccountRepository userRepository;

    @Mock
    private FilterChain filterChain;

    @AfterEach
    void clearSecurityContext() {
        AuthUserContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void restoresAdminAuthorityFromActiveAccount() throws Exception {
        when(sessionService.readToken(any())).thenReturn("session-token");
        when(sessionService.resolve("session-token")).thenReturn(Optional.of("admin-1"));
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(user("admin-1", "ADMIN", "ACTIVE")));

        doAnswer(invocation -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString).contains("ROLE_ADMIN");
            assertThat(AuthUserContext.currentUserId()).contains("admin-1");
            return null;
        }).when(filterChain).doFilter(any(), any());

        new AuthSessionFilter(sessionService, userRepository).doFilter(
            new MockHttpServletRequest(), new MockHttpServletResponse(), filterChain
        );

        verify(filterChain).doFilter(any(), any());
        assertThat(AuthUserContext.currentUserId()).isEmpty();
    }

    @Test
    void clearsSessionForDisabledAccount() throws Exception {
        when(sessionService.readToken(any())).thenReturn("session-token");
        when(sessionService.resolve("session-token")).thenReturn(Optional.of("user-1"));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user("user-1", "USER", "DISABLED")));

        new AuthSessionFilter(sessionService, userRepository).doFilter(
            new MockHttpServletRequest(), new MockHttpServletResponse(), filterChain
        );

        verify(sessionService).invalidate(any(), any());
        verify(filterChain).doFilter(any(), any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private UserAccountEntity user(String id, String role, String status) {
        return UserAccountEntity.builder()
            .id(id)
            .email(id + "@example.com")
            .passwordHash("hash")
            .role(role)
            .status(status)
            .build();
    }
}
