package interview.guide.modules.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.result.Result;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证过滤链。
 *
 * <p>迁移期开启兼容模式时不改变现有匿名访问；切换 app.auth.required=true 后，
 * 认证公共接口以外的请求必须拥有 Redis 登录会话。</p>
 */
@Configuration
@RequiredArgsConstructor
public class AuthSecurityConfig {
    private final AuthSessionFilter authSessionFilter;
    private final CsrfCookieFilter csrfCookieFilter;
    private final AuthProperties properties;
    private final ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * 登录由 AuthService 直接完成；显式提供该 Bean，避免 Spring Boot 自动生成一套
     * 未使用的默认用户和开发密码，降低部署日志误导。
     */
    @Bean
    public UserDetailsService unusedFormLoginUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Form login is disabled; use /api/auth/login");
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (!properties.isRequired()) {
            http.csrf(AbstractHttpConfigurer::disable);
        } else {
            CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
            http.csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(
                    "/api/auth/login", "/api/auth/register", "/api/auth/logout",
                    "/api/auth/verify-email", "/api/auth/resend-verification",
                    "/api/auth/password-reset/**"
                )
            );
        }

        http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(context -> context.requireExplicitSave(false))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/auth/**",
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html"
                ).permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().access((authentication, context) ->
                    new org.springframework.security.authorization.AuthorizationDecision(
                        !properties.isRequired() && !isProtectedWebSocket(context.getRequest().getRequestURI())
                            || isAuthenticated(authentication)
                    )
                )
            )
            .exceptionHandling(exception -> exception.authenticationEntryPoint(authenticationEntryPoint()))
            .addFilterAfter(csrfCookieFilter, org.springframework.security.web.csrf.CsrfFilter.class)
            .addFilterBefore(authSessionFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private boolean isProtectedWebSocket(String uri) {
        return uri != null && uri.startsWith("/ws/") && properties.isRequired();
    }

    private boolean isAuthenticated(java.util.function.Supplier<? extends Authentication> authentication) {
        Authentication value = authentication.get();
        return value != null
            && value.isAuthenticated()
            && !(value instanceof AnonymousAuthenticationToken);
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, exception) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            writeUnauthorized(response);
        };
    }

    private void writeUnauthorized(jakarta.servlet.http.HttpServletResponse response) throws IOException {
        response.getWriter().write(objectMapper.writeValueAsString(
            Result.error(interview.guide.common.exception.ErrorCode.UNAUTHORIZED, "请先登录")
        ));
    }
}
