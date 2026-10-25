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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private UserAccountRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

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
        verify(passwordEncoder).encode("StrongPass123");
        verify(userRepository).save(any(UserAccountEntity.class));
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
}
