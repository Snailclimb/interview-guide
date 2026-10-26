package interview.guide.modules.auth;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.model.SecurityAuditEventEntity;
import interview.guide.modules.auth.model.AdminUserResponse;
import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.repository.SecurityAuditEventRepository;
import interview.guide.modules.auth.repository.UserAccountRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminGovernanceServiceTest {
    @Mock
    private UserAccountRepository userRepository;

    @Mock
    private SecurityAuditEventRepository auditRepository;

    @Mock
    private AuthSessionService sessionService;

    @AfterEach
    void clearContext() {
        AuthUserContext.clear();
    }

    @Test
    void disablesUserAndRevokesItsSessions() {
        UserAccountEntity target = user("user-2", "USER", "ACTIVE");
        when(userRepository.findById("user-2")).thenReturn(Optional.of(target));
        when(userRepository.save(target)).thenReturn(target);
        AuthUserContext.set("admin-1");

        AdminUserResponse result = new AdminGovernanceService(
            userRepository, auditRepository, sessionService
        ).updateStatus("user-2", "DISABLED");

        assertThat(result.status()).isEqualTo("DISABLED");
        verify(sessionService).invalidateAll("user-2");
        verify(auditRepository).save(any(SecurityAuditEventEntity.class));
    }

    @Test
    void protectsLastActiveAdminFromDemotion() {
        UserAccountEntity target = user("admin-1", "ADMIN", "ACTIVE");
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(target));
        when(userRepository.countByRoleIgnoreCaseAndStatusIgnoreCase("ADMIN", "ACTIVE"))
            .thenReturn(1L);
        AuthUserContext.set("another-admin");

        assertThatThrownBy(() -> new AdminGovernanceService(
            userRepository, auditRepository, sessionService
        ).updateRole("admin-1", "USER"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("最后一个管理员");
    }

    @Test
    void rejectsChangingCurrentAdminAccount() {
        UserAccountEntity target = user("admin-1", "ADMIN", "ACTIVE");
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(target));
        AuthUserContext.set("admin-1");

        assertThatThrownBy(() -> new AdminGovernanceService(
            userRepository, auditRepository, sessionService
        ).updateStatus("admin-1", "DISABLED"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("当前管理员");
    }

    @Test
    void limitsUserAndAuditQueries() {
        UserAccountEntity first = user("user-1", "USER", "ACTIVE");
        when(userRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(first));
        when(auditRepository.findTop200ByOrderByCreatedAtDesc()).thenReturn(List.of());

        AdminGovernanceService service = new AdminGovernanceService(
            userRepository, auditRepository, sessionService
        );

        assertThat(service.listUsers(0)).hasSize(1);
        assertThat(service.listAuditEvents(999)).isEmpty();
    }

    private UserAccountEntity user(String id, String role, String status) {
        return UserAccountEntity.builder()
            .id(id)
            .email(id + "@example.com")
            .passwordHash("hash")
            .role(role)
            .status(status)
            .build();
    }
}
