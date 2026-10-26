package interview.guide.modules.auth;

import java.time.Duration;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录认证配置。
 *
 * <p>required=false 是迁移期兼容开关，开启后除认证公共接口外的 API 都必须有登录会话。
 * 正式 HTTPS 环境应同时开启 secure-cookie。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {
    private boolean required;
    private String cookieName = "INTERVIEW_AUTH";
    private Duration sessionTtl = Duration.ofDays(7);
    private boolean secureCookie;
    private String sameSite = "Lax";
    private boolean requireEmailVerification;
    private List<String> adminEmails = List.of();
    private Mail mail = new Mail();

    public boolean isBootstrapAdminEmail(String email) {
        if (email == null || email.isBlank() || adminEmails == null) {
            return false;
        }
        return adminEmails.stream().anyMatch(candidate -> candidate != null
            && candidate.trim().equalsIgnoreCase(email.trim()));
    }

    @Data
    public static class Mail {
        private boolean enabled;
        private String from = "no-reply@example.com";
        private String frontendBaseUrl = "http://localhost";
        private Duration tokenTtl = Duration.ofMinutes(30);
        private Duration resendCooldown = Duration.ofSeconds(60);
    }
}
