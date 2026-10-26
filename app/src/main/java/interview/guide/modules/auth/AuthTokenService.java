package interview.guide.modules.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/** Redis-backed one-time email verification and password reset tokens. */
@Service
public class AuthTokenService {
    private static final String TOKEN_PREFIX = "auth:token:";
    private static final String COOLDOWN_PREFIX = "auth:mail-cooldown:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RedissonClient redissonClient;
    private final AuthProperties properties;

    public AuthTokenService(RedissonClient redissonClient, AuthProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    public String issueVerification(String userId) {
        return issue("verify", userId);
    }

    public String issuePasswordReset(String userId) {
        return issue("reset", userId);
    }

    public Optional<String> consumeVerification(String token) {
        return consume("verify", token);
    }

    public Optional<String> consumePasswordReset(String token) {
        return consume("reset", token);
    }

    public boolean acquireMailCooldown(String normalizedEmail) {
        String key = COOLDOWN_PREFIX + digest(normalizedEmail);
        RBucket<String> bucket = redissonClient.getBucket(key);
        Duration cooldown = safeDuration(properties.getMail().getResendCooldown(), Duration.ofSeconds(60));
        return bucket.setIfAbsent("1", cooldown);
    }

    private String issue(String kind, String userId) {
        String token = randomToken();
        Duration ttl = safeDuration(properties.getMail().getTokenTtl(), Duration.ofMinutes(30));
        redissonClient.getBucket(TOKEN_PREFIX + kind + ":" + digest(token))
            .set(userId, ttl.toSeconds(), TimeUnit.SECONDS);
        return token;
    }

    private Optional<String> consume(String kind, String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        RBucket<String> bucket = redissonClient.getBucket(TOKEN_PREFIX + kind + ":" + digest(token.trim()));
        String userId = bucket.getAndDelete();
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(userId);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private Duration safeDuration(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
