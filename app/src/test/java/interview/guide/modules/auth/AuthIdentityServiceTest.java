package interview.guide.modules.auth;

import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthIdentityServiceTest {
    private final AuthProperties properties = new AuthProperties();
    private final AuthIdentityService service = new AuthIdentityService(properties);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousAuthenticationDoesNotOverrideLegacyIdentity() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
            "test-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        ));

        assertThat(service.requireIdentity("legacy-user")).isEqualTo("legacy-user");
    }

    @Test
    void requiredModeRejectsAnonymousAuthentication() {
        properties.setRequired(true);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
            "test-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        ));

        assertThatThrownBy(() -> service.requireIdentity(null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请先登录");
    }

    @Test
    void returnsFormalSessionPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated("user-1", null, List.of())
        );

        assertThat(service.requireIdentity("spoofed-user")).isEqualTo("user-1");
        assertThat(service.isAuthenticated()).isTrue();
    }
}
