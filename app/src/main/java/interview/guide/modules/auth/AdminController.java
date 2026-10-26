package interview.guide.modules.auth;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.model.AdminUserResponse;
import interview.guide.modules.auth.model.SecurityAuditEventResponse;
import interview.guide.modules.auth.model.UpdateAdminUserRoleRequest;
import interview.guide.modules.auth.model.UpdateAdminUserStatusRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理员账号治理接口；访问权限由 Spring Security 的 ROLE_ADMIN 控制。 */
@RestController
@RequiredArgsConstructor
public class AdminController {
    private final AdminGovernanceService governanceService;

    @GetMapping("/api/admin/users")
    public Result<List<AdminUserResponse>> listUsers(
        @RequestParam(value = "limit", defaultValue = "100") int limit
    ) {
        return Result.success(governanceService.listUsers(limit));
    }

    @PatchMapping("/api/admin/users/{userId}/status")
    public Result<AdminUserResponse> updateStatus(
        @PathVariable String userId,
        @Valid @RequestBody UpdateAdminUserStatusRequest request
    ) {
        return Result.success(governanceService.updateStatus(userId, request.status()));
    }

    @PatchMapping("/api/admin/users/{userId}/role")
    public Result<AdminUserResponse> updateRole(
        @PathVariable String userId,
        @Valid @RequestBody UpdateAdminUserRoleRequest request
    ) {
        return Result.success(governanceService.updateRole(userId, request.role()));
    }

    @GetMapping("/api/admin/audit/events")
    public Result<List<SecurityAuditEventResponse>> listAuditEvents(
        @RequestParam(value = "limit", defaultValue = "100") int limit
    ) {
        return Result.success(governanceService.listAuditEvents(limit));
    }
}
