package interview.guide.modules.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 从 Redis 会话恢复 Spring Security 身份。 */
@Component
@RequiredArgsConstructor
public class AuthSessionFilter extends OncePerRequestFilter {
    private final AuthSessionService sessionService;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String token = sessionService.readToken(request);
        var sessionUser = sessionService.resolve(token);
        sessionUser.ifPresent(userId -> {
            AuthUserContext.set(userId);
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        });
        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthUserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private String legacyIdentity(HttpServletRequest request) {
        String identity = request.getHeader("X-User-Id");
        if (identity == null || identity.isBlank()) {
            return null;
        }
        String normalized = identity.trim();
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }
}
