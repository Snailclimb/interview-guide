package interview.guide.modules.auth;

import java.time.Duration;
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
}
