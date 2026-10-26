package interview.guide.modules.auth;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Sends auth links when SMTP is explicitly enabled. Disabled mail keeps local migration usable. */
@Slf4j
@Service
public class AuthMailService {
    private final JavaMailSender mailSender;
    private final AuthProperties properties;

    @Autowired
    public AuthMailService(
        @Autowired(required = false) JavaMailSender mailSender,
        AuthProperties properties
    ) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.getMail() != null && properties.getMail().isEnabled();
    }

    public void sendVerification(String email, String token) {
        send(email, "验证你的 AI Interview 账号", "/verify-email?token=" + encode(token),
            "请点击以下链接验证邮箱：");
    }

    public void sendPasswordReset(String email, String token) {
        send(email, "重置你的 AI Interview 密码", "/reset-password?token=" + encode(token),
            "请点击以下链接设置新密码：");
    }

    private void send(String email, String subject, String path, String intro) {
        if (!enabled()) {
            log.info("认证邮件未启用，跳过发送: type={}, recipientHash={}", subject, Integer.toHexString(email.hashCode()));
            return;
        }
        if (mailSender == null) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_DELIVERY_UNAVAILABLE);
        }
        String baseUrl = properties.getMail().getFrontendBaseUrl();
        String link = (baseUrl == null ? "" : baseUrl.replaceAll("/+$", "")) + path;
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getMail().getFrom());
        message.setTo(email);
        message.setSubject(subject);
        message.setText(intro + "\n\n" + link + "\n\n链接将在 "
            + properties.getMail().getTokenTtl().toMinutes() + " 分钟后失效。\n");
        try {
            mailSender.send(message);
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_DELIVERY_UNAVAILABLE);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
