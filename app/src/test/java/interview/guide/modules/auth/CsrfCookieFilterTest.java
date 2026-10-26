package interview.guide.modules.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CsrfCookieFilterTest {
    @Test
    void rewritesTheTokenAfterTheChainSoLateEmptyCookieCannotWin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        CsrfToken token = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "stable-token");
        request.setAttribute(CsrfToken.class.getName(), token);

        new CsrfCookieFilter().doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getCookies())
            .anySatisfy(cookie -> {
                assertThat(cookie.getName()).isEqualTo("XSRF-TOKEN");
                assertThat(cookie.getValue()).isEqualTo("stable-token");
                assertThat(cookie.getPath()).isEqualTo("/");
                assertThat(cookie.isHttpOnly()).isFalse();
            });
    }
}
