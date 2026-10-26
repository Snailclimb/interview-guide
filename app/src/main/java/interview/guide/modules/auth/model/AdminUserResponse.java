package interview.guide.modules.auth.model;

import java.time.LocalDateTime;

public record AdminUserResponse(
    String id,
    String email,
    String nickname,
    String status,
    String role,
    boolean emailVerified,
    LocalDateTime lastLoginAt,
    LocalDateTime createdAt
) {
    public static AdminUserResponse from(UserAccountEntity user) {
        return new AdminUserResponse(
            user.getId(), user.getEmail(), user.getNickname(), user.getStatus(), user.getRole(),
            user.isEmailVerified(), user.getLastLoginAt(), user.getCreatedAt()
        );
    }
}
