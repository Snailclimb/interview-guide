package interview.guide.modules.auth;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthTokenServiceTest {
    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RBucket<String> bucket;

    @Test
    void issuesRandomTokenAndStoresOnlyHashedKey() {
        AuthProperties properties = new AuthProperties();
        properties.getMail().setTokenTtl(Duration.ofMinutes(10));
        when(redissonClient.<String>getBucket(anyString())).thenReturn(bucket);

        String token = new AuthTokenService(redissonClient, properties).issueVerification("user-1");

        assertThat(token).isNotBlank().doesNotContain("user-1");
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redissonClient).getBucket(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("auth:token:verify:").doesNotContain(token);
        verify(bucket).set(eq("user-1"), eq(600L), eq(TimeUnit.SECONDS));
    }

    @Test
    void consumesExistingTokenOnlyOnce() {
        AuthProperties properties = new AuthProperties();
        when(redissonClient.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.getAndDelete()).thenReturn("user-1");

        Optional<String> result = new AuthTokenService(redissonClient, properties)
            .consumePasswordReset("reset-token");

        assertThat(result).contains("user-1");
        verify(bucket).getAndDelete();
    }

    @Test
    void appliesCooldownAtomically() {
        AuthProperties properties = new AuthProperties();
        properties.getMail().setResendCooldown(Duration.ofSeconds(45));
        when(redissonClient.<String>getBucket(anyString())).thenReturn(bucket);
        when(bucket.setIfAbsent(eq("1"), eq(Duration.ofSeconds(45)))).thenReturn(true);

        assertThat(new AuthTokenService(redissonClient, properties)
            .acquireMailCooldown("user@example.com")).isTrue();
    }
}
