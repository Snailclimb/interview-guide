package interview.guide.modules.auth;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** 业务模块统一获取身份；认证完成后优先使用服务端会话，旧 Header 仅用于迁移期兼容。 */
@Service
@RequiredArgsConstructor
public class AuthIdentityService {
    private final AuthProperties properties;

    public String requireIdentity(String legacyIdentity) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (isFormalAuthentication(authentication)
            && authentication.getPrincipal() instanceof String userId
            && !userId.isBlank()) {
            return userId;
        }
        if (properties.isRequired()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        if (legacyIdentity == null || legacyIdentity.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少登录身份");
        }
        return legacyIdentity.trim().length() > 64
            ? legacyIdentity.trim().substring(0, 64)
            : legacyIdentity.trim();
    }

    public boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return isFormalAuthentication(authentication)
            && authentication.getPrincipal() instanceof String;
    }

    private boolean isFormalAuthentication(Authentication authentication) {
        return authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
