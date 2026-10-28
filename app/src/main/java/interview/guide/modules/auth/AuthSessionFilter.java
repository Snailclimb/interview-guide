package interview.guide.modules.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.repository.UserAccountRepository;
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
    private final UserAccountRepository userRepository;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String token = sessionService.readToken(request);
        var sessionUser = sessionService.resolve(token);
        if (sessionUser.isPresent()) {
            Optional<UserAccountEntity> account = userRepository.findById(sessionUser.get());
            if (account.isPresent() && "ACTIVE".equalsIgnoreCase(account.get().getStatus())) {
                authenticate(request, account.get());
            } else {
                sessionService.invalidate(request, response);
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthUserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(HttpServletRequest request, UserAccountEntity user) {
        AuthUserContext.set(user.getId());
        String role = "ADMIN".equalsIgnoreCase(user.getRole()) ? "ROLE_ADMIN" : "ROLE_USER";
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(
                user.getId(),
                null,
                List.of(new SimpleGrantedAuthority(role))
            );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * SSE 和其他 Spring MVC 异步请求会在响应完成前再次进入异步分派。
     * 初始请求线程上的认证上下文不能直接作为异步分派的安全凭证，必须重新
     * 校验服务端 Cookie 会话，否则响应已经开始写出后可能被 Spring Security
     * 判定为匿名请求并提前关闭连接。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
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
