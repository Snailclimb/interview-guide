package interview.guide.modules.auth;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.AdminUserResponse;
import interview.guide.modules.auth.model.SecurityAuditEventEntity;
import interview.guide.modules.auth.model.SecurityAuditEventResponse;
import interview.guide.modules.auth.model.UserAccountEntity;
import interview.guide.modules.auth.repository.SecurityAuditEventRepository;
import interview.guide.modules.auth.repository.UserAccountRepository;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理员用户治理；只处理账号状态、角色和低敏安全审计，不接触业务内容。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminGovernanceService {
    private static final int MAX_LIMIT = 200;

    private final UserAccountRepository userRepository;
    private final SecurityAuditEventRepository auditRepository;
    private final AuthSessionService sessionService;

    @Transactional(readOnly = true)
    public List<AdminUserResponse> listUsers(int limit) {
        int safeLimit = Math.max(1, Math.min(MAX_LIMIT, limit));
        return userRepository.findAllByOrderByCreatedAtDesc().stream()
            .limit(safeLimit)
            .map(AdminUserResponse::from)
            .toList();
    }

    @Transactional
    public AdminUserResponse updateStatus(String targetUserId, String status) {
        UserAccountEntity target = requireUser(targetUserId);
        ensureNotCurrentUser(target);
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(normalizedStatus) && !"DISABLED".equals(normalizedStatus)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号状态不合法");
        }
        if ("DISABLED".equals(normalizedStatus)
            && "ADMIN".equalsIgnoreCase(target.getRole())
            && isLastActiveAdmin(target)) {
            throw new BusinessException(ErrorCode.AUTH_ADMIN_LAST_ACCOUNT);
        }
        target.setStatus(normalizedStatus);
        UserAccountEntity saved = userRepository.save(target);
        sessionService.invalidateAll(target.getId());
        record("ADMIN_USER_STATUS_UPDATED", target.getId(), normalizedStatus);
        return AdminUserResponse.from(saved);
    }

    @Transactional
    public AdminUserResponse updateRole(String targetUserId, String role) {
        UserAccountEntity target = requireUser(targetUserId);
        ensureNotCurrentUser(target);
        String normalizedRole = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        if (!"USER".equals(normalizedRole) && !"ADMIN".equals(normalizedRole)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号角色不合法");
        }
        if ("USER".equals(normalizedRole)
            && "ADMIN".equalsIgnoreCase(target.getRole())
            && isLastActiveAdmin(target)) {
            throw new BusinessException(ErrorCode.AUTH_ADMIN_LAST_ACCOUNT);
        }
        target.setRole(normalizedRole);
        UserAccountEntity saved = userRepository.save(target);
        sessionService.invalidateAll(target.getId());
        record("ADMIN_USER_ROLE_UPDATED", target.getId(), normalizedRole);
        return AdminUserResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<SecurityAuditEventResponse> listAuditEvents(int limit) {
        int safeLimit = Math.max(1, Math.min(MAX_LIMIT, limit));
        return auditRepository.findTop200ByOrderByCreatedAtDesc().stream()
            .limit(safeLimit)
            .map(SecurityAuditEventResponse::from)
            .toList();
    }

    private UserAccountEntity requireUser(String userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_ADMIN_USER_NOT_FOUND));
    }

    private boolean isLastActiveAdmin(UserAccountEntity target) {
        return userRepository.countByRoleIgnoreCaseAndStatusIgnoreCase("ADMIN", "ACTIVE") <= 1;
    }

    private void ensureNotCurrentUser(UserAccountEntity target) {
        if (target.getId().equals(AuthUserContext.currentUserId().orElse(null))) {
            throw new BusinessException(ErrorCode.AUTH_ADMIN_REQUIRED, "不能修改当前管理员账号");
        }
    }

    private void record(String action, String targetUserId, String result) {
        String actorUserId = AuthUserContext.currentUserId().orElse(null);
        try {
            auditRepository.save(SecurityAuditEventEntity.builder()
                .actorUserId(actorUserId)
                .targetUserId(targetUserId)
                .action(action)
                .resourceType("user-account")
                .resourceId(targetUserId)
                .result(result)
                .build());
        } catch (RuntimeException error) {
            log.warn("管理员安全审计写入失败: action={}, targetUserId={}", action, targetUserId, error);
        }
    }
}
