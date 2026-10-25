package interview.guide.modules.auth;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.AuthStateResponse;
import interview.guide.modules.auth.model.AuthUserResponse;
import interview.guide.modules.auth.model.LoginRequest;
import interview.guide.modules.auth.model.RegisterRequest;
import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.repository.UserAccountRepository;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    @Transactional
    public AuthUserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_EXISTS);
        }
        if (request.password().chars().distinct().count() < 4) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_TOO_WEAK);
        }
        UserAccountEntity user = UserAccountEntity.builder()
            .email(email)
            .passwordHash(passwordEncoder.encode(request.password()))
            .nickname(normalizeNickname(request.nickname(), email))
            .build();
        return AuthUserResponse.from(userRepository.save(user));
    }

    @Transactional
    public UserAccountEntity authenticate(LoginRequest request) {
        String email = normalizeEmail(request.email());
        UserAccountEntity user = userRepository.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        user.setLastLoginAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public AuthStateResponse current(String userId) {
        if (userId == null || userId.isBlank()) {
            return new AuthStateResponse(properties.isRequired(), false, null);
        }
        return userRepository.findById(userId)
            .map(user -> new AuthStateResponse(
                properties.isRequired(), true, AuthUserResponse.from(user)
            ))
            .orElse(new AuthStateResponse(properties.isRequired(), false, null));
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeNickname(String nickname, String email) {
        if (nickname != null && !nickname.isBlank()) {
            return nickname.trim();
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : "面试练习者";
    }
}
