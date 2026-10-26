package interview.guide.modules.auth.model;

import java.time.LocalDateTime;

public record AuthUserResponse(
    String id,
    String email,
    String nickname,
    String status,
    String role,
    boolean emailVerified,
    LocalDateTime lastLoginAt
) {
    public static AuthUserResponse from(UserAccountEntity user) {
        return new AuthUserResponse(
            user.getId(), user.getEmail(), user.getNickname(), user.getStatus(), user.getRole(),
            user.isEmailVerified(), user.getLastLoginAt()
        );
    }

    public AuthUserResponse(
        String id,
        String email,
        String nickname,
        String status,
        boolean emailVerified,
        LocalDateTime lastLoginAt
    ) {
        this(id, email, nickname, status, "USER", emailVerified, lastLoginAt);
    }
}
