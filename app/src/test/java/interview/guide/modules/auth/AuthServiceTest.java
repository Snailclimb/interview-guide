package interview.guide.modules.auth;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.model.LoginRequest;
import interview.guide.modules.auth.model.RegisterRequest;
import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private UserAccountRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private LegacyDataOwnershipService legacyDataOwnershipService;

    @Mock
    private AuthTokenService tokenService;

    @Mock
    private AuthMailService mailService;

    @Mock
    private AuthSessionService sessionService;

    private final AuthProperties properties = new AuthProperties();

    @Test
    void registersNormalizedEmailAndStoresOnlyHash() {
        when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("StrongPass123")).thenReturn("$2a$12$hash");
        when(userRepository.save(any(UserAccountEntity.class))).thenAnswer(invocation -> {
            UserAccountEntity user = invocation.getArgument(0);
            user.setId("user-1");
            return user;
        });

        var result = new AuthService(userRepository, passwordEncoder, properties)
            .register(new RegisterRequest(" User@Example.com ", "StrongPass123", "小明"));

        assertThat(result.email()).isEqualTo("user@example.com");
        assertThat(result.nickname()).isEqualTo("小明");
        assertThat(result.role()).isEqualTo("USER");
        verify(passwordEncoder).encode("StrongPass123");
        verify(userRepository).save(any(UserAccountEntity.class));
        assertThat(result.emailVerified()).isTrue();
    }

    @Test
    void rejectsDuplicateEmailBeforeHashingPassword() {
        when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> new AuthService(userRepository, passwordEncoder, properties)
            .register(new RegisterRequest("USER@example.com", "StrongPass123", null)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已注册");

        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void authenticatesOnlyActiveUserWithMatchingPassword() {
        UserAccountEntity user = UserAccountEntity.builder()
            .id("user-1")
            .email("user@example.com")
            .passwordHash("stored-hash")
            .status("ACTIVE")
            .build();
        when(userRepository.findByEmailIgnoreCase("user@example.com")).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches(eq("StrongPass123"), eq("stored-hash"))).thenReturn(true);
        when(userRepository.save(user)).thenReturn(user);

        UserAccountEntity authenticated = new AuthService(userRepository, passwordEncoder, properties)
            .authenticate(new LoginRequest("user@example.com", "StrongPass123"));

        assertThat(authenticated.getLastLoginAt()).isNotNull();
        verify(userRepository).save(user);
    }

    @Test
    void requiresEmailVerificationBeforeStartingSession() {
        properties.setRequireEmailVerification(true);
        when(userRepository.existsByEmailIgnoreCase("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("StrongPass123")).thenReturn("$2a$12$hash");
        when(userRepository.save(any(UserAccountEntity.class))).thenAnswer(invocation -> {
            UserAccountEntity user = invocation.getArgument(0);
            user.setId("user-1");
            return user;
        });
        when(mailService.enabled()).thenReturn(true);
        when(tokenService.acquireMailCooldown("user@example.com")).thenReturn(true);
        when(tokenService.issueVerification("user-1")).thenReturn("verification-token");

        AuthService service = new AuthService(
            userRepository, passwordEncoder, properties, legacyDataOwnershipService,
            tokenService, mailService, sessionService
        );
        var result = service.register(new RegisterRequest("user@example.com", "StrongPass123", null));

        assertThat(result.emailVerified()).isFalse();
        assertThat(service.shouldStartSession(result)).isFalse();
        verify(mailService).sendVerification("user@example.com", "verification-token");
    }

    @Test
    void verifiesEmailAndConsumesOneTimeToken() {
        properties.setRequireEmailVerification(true);
        UserAccountEntity user = UserAccountEntity.builder()
            .id("user-1")
            .email("user@example.com")
            .passwordHash("stored-hash")
            .status("ACTIVE")
            .build();
        when(tokenService.consumeVerification("token")).thenReturn(Optional.of("user-1"));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        new AuthService(
            userRepository, passwordEncoder, properties, legacyDataOwnershipService,
            tokenService, mailService, sessionService
        ).verifyEmail("token");

        assertThat(user.isEmailVerified()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    void resetPasswordInvalidatesExistingSessions() {
        UserAccountEntity user = UserAccountEntity.builder()
            .id("user-1")
            .email("user@example.com")
            .passwordHash("old-hash")
            .status("ACTIVE")
            .build();
        when(tokenService.consumePasswordReset("token")).thenReturn(Optional.of("user-1"));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewStrong123")).thenReturn("new-hash");
        when(userRepository.save(user)).thenReturn(user);

        new AuthService(
            userRepository, passwordEncoder, properties, legacyDataOwnershipService,
            tokenService, mailService, sessionService
        ).resetPassword("token", "NewStrong123");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(sessionService).invalidateAll("user-1");
    }

    @Test
    void passwordResetRequestDoesNotRevealUnknownEmail() {
        when(userRepository.findByEmailIgnoreCase("missing@example.com")).thenReturn(Optional.empty());

        new AuthService(
            userRepository, passwordEncoder, properties, legacyDataOwnershipService,
            tokenService, mailService, sessionService
        ).requestPasswordReset("missing@example.com");

        verifyNoInteractions(tokenService, mailService);
    }

    @Test
    void configuredAdminEmailGetsAdminRoleOnRegistration() {
        properties.setAdminEmails(java.util.List.of("admin@example.com"));
        when(userRepository.existsByEmailIgnoreCase("admin@example.com")).thenReturn(false);
        when(passwordEncoder.encode("StrongPass123")).thenReturn("$2a$12$hash");
        when(userRepository.save(any(UserAccountEntity.class))).thenAnswer(invocation -> {
            UserAccountEntity user = invocation.getArgument(0);
            user.setId("admin-1");
            return user;
        });

        var result = new AuthService(userRepository, passwordEncoder, properties)
            .register(new RegisterRequest("ADMIN@example.com", "StrongPass123", null));

        assertThat(result.role()).isEqualTo("ADMIN");
    }
}
