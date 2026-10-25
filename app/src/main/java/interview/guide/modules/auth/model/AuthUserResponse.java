package interview.guide.modules.auth.model;

import java.time.LocalDateTime;

public record AuthUserResponse(
    String id,
    String email,
    String nickname,
    String status,
    boolean emailVerified,
    LocalDateTime lastLoginAt
) {
    public static AuthUserResponse from(UserAccountEntity user) {
        return new AuthUserResponse(
            user.getId(), user.getEmail(), user.getNickname(), user.getStatus(),
            user.isEmailVerified(), user.getLastLoginAt()
        );
    }
}
