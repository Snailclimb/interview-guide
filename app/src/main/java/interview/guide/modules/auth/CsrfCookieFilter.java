package interview.guide.modules.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 触发懒加载的 CSRF token 写入 Cookie，供 SPA 的 Axios/fetch 读取。 */
@Component
public class CsrfCookieFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String tokenValue = null;
        if (token != null) {
            tokenValue = token.getToken();
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Spring Security 7 的延迟 token 在部分 GET 响应结束时会追加空 Cookie。
            // SPA 需要读取原始 token 才能提交 PATCH/POST，因此在响应链最后稳定回写。
            if (tokenValue != null && !tokenValue.isBlank()) {
                Cookie cookie = new Cookie("XSRF-TOKEN", tokenValue);
                cookie.setPath("/");
                cookie.setHttpOnly(false);
                response.addCookie(cookie);
            }
        }
    }
}
