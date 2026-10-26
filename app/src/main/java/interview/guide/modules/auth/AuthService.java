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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class AuthService {
    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final LegacyDataOwnershipService legacyDataOwnershipService;
    private final AuthTokenService tokenService;
    private final AuthMailService mailService;
    private final AuthSessionService sessionService;

    public AuthService(
        UserAccountRepository userRepository,
        PasswordEncoder passwordEncoder,
        AuthProperties properties
    ) {
        this(userRepository, passwordEncoder, properties, null, null, null, null);
    }

    @Autowired
    public AuthService(
        UserAccountRepository userRepository,
        PasswordEncoder passwordEncoder,
        AuthProperties properties,
        LegacyDataOwnershipService legacyDataOwnershipService,
        AuthTokenService tokenService,
        AuthMailService mailService,
        AuthSessionService sessionService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.legacyDataOwnershipService = legacyDataOwnershipService;
        this.tokenService = tokenService;
        this.mailService = mailService;
        this.sessionService = sessionService;
    }

    @Transactional
    public AuthUserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (properties.isRequireEmailVerification()
            && (mailService == null || !mailService.enabled())) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_DELIVERY_UNAVAILABLE);
        }
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
            .role(properties.isBootstrapAdminEmail(email) ? "ADMIN" : "USER")
            .emailVerified(!properties.isRequireEmailVerification())
            .build();
        UserAccountEntity saved = userRepository.save(user);
        claimLegacyData(saved);
        issueVerificationIfAvailable(saved);
        return AuthUserResponse.from(saved);
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
        if (properties.isBootstrapAdminEmail(email) && !"ADMIN".equalsIgnoreCase(user.getRole())) {
            user.setRole("ADMIN");
        }
        if (properties.isRequireEmailVerification() && !user.isEmailVerified()) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID, "请先验证邮箱后再登录");
        }
        user.setLastLoginAt(LocalDateTime.now());
        UserAccountEntity saved = userRepository.save(user);
        claimLegacyData(saved);
        return saved;
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

    @Transactional
    public void verifyEmail(String token) {
        String userId = requireToken(tokenService == null ? null : tokenService.consumeVerification(token));
        UserAccountEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_TOKEN_INVALID));
        user.setEmailVerified(true);
        userRepository.save(user);
    }

    @Transactional
    public void resendVerification(String email) {
        UserAccountEntity user = findActiveByEmail(email);
        if (user == null || user.isEmailVerified()) {
            return;
        }
        issueVerificationIfAvailable(user);
    }

    @Transactional
    public void requestPasswordReset(String email) {
        UserAccountEntity user = findActiveByEmail(email);
        if (user == null || tokenService == null || mailService == null || !mailService.enabled()) {
            return;
        }
        if (!tokenService.acquireMailCooldown(normalizeEmail(email))) {
            return;
        }
        String token = tokenService.issuePasswordReset(user.getId());
        mailService.sendPasswordReset(user.getEmail(), token);
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        validatePassword(newPassword);
        String userId = requireToken(tokenService == null ? null : tokenService.consumePasswordReset(token));
        UserAccountEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_TOKEN_INVALID));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        if (sessionService != null) {
            sessionService.invalidateAll(userId);
        }
    }

    public boolean shouldStartSession(AuthUserResponse user) {
        return user != null && (!properties.isRequireEmailVerification() || user.emailVerified());
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private UserAccountEntity findActiveByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(normalizeEmail(email))
            .filter(user -> "ACTIVE".equalsIgnoreCase(user.getStatus()))
            .orElse(null);
    }

    private void issueVerificationIfAvailable(UserAccountEntity user) {
        if (user == null || user.isEmailVerified() || tokenService == null || mailService == null
            || !mailService.enabled()) {
            return;
        }
        if (!tokenService.acquireMailCooldown(user.getEmail())) {
            return;
        }
        mailService.sendVerification(user.getEmail(), tokenService.issueVerification(user.getId()));
    }

    private String requireToken(java.util.Optional<String> userId) {
        return userId == null || userId.isEmpty()
            ? throwTokenInvalid()
            : userId.get();
    }

    private String throwTokenInvalid() {
        throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128
            || password.chars().distinct().count() < 4) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_TOO_WEAK);
        }
    }

    private void claimLegacyData(UserAccountEntity user) {
        if (legacyDataOwnershipService != null) {
            legacyDataOwnershipService.claimForSingleUser(user.getId());
        }
    }

    private String normalizeNickname(String nickname, String email) {
        if (nickname != null && !nickname.isBlank()) {
            return nickname.trim();
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : "面试练习者";
    }
}
