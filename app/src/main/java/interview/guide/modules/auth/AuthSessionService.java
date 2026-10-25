package interview.guide.modules.auth;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * 基于现有 Redis/Redisson 的服务端登录会话。
 * Cookie 只保存不可预测的随机会话标识，用户身份和 TTL 都保存在 Redis。
 */
@Service
@RequiredArgsConstructor
public class AuthSessionService {
    private static final String KEY_PREFIX = "auth:session:";

    private final RedissonClient redissonClient;
    private final AuthProperties properties;

    public String create(String userId, HttpServletResponse response) {
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        bucket(token).set(userId, ttlSeconds(), TimeUnit.SECONDS);
        writeCookie(response, token, ttlSeconds());
        return token;
    }

    public Optional<String> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        RBucket<String> session = bucket(token);
        String userId = session.get();
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        // 滑动过期，避免用户持续使用时被无故踢出。
        session.expire(Duration.ofSeconds(ttlSeconds()));
        return Optional.of(userId);
    }

    public void invalidate(HttpServletRequest request, HttpServletResponse response) {
        String token = readToken(request);
        if (token != null) {
            bucket(token).delete();
        }
        writeCookie(response, "", 0);
    }

    public String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (properties.getCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private RBucket<String> bucket(String token) {
        return redissonClient.getBucket(KEY_PREFIX + token);
    }

    private long ttlSeconds() {
        long seconds = properties.getSessionTtl() == null
            ? Duration.ofDays(7).toSeconds()
            : properties.getSessionTtl().toSeconds();
        return Math.max(300, seconds);
    }

    private void writeCookie(HttpServletResponse response, String token, long maxAge) {
        StringBuilder value = new StringBuilder()
            .append(properties.getCookieName()).append('=').append(token)
            .append("; Max-Age=").append(maxAge)
            .append("; Path=/; HttpOnly; SameSite=")
            .append(normalizeSameSite());
        if (properties.isSecureCookie()) {
            value.append("; Secure");
        }
        response.addHeader("Set-Cookie", value.toString());
    }

    private String normalizeSameSite() {
        String sameSite = properties.getSameSite();
        if (sameSite == null || sameSite.isBlank()) {
            return "Lax";
        }
        return switch (sameSite.trim().toLowerCase()) {
            case "strict" -> "Strict";
            case "none" -> "None";
            default -> "Lax";
        };
    }
}
