package interview.guide.modules.auth;

import java.util.Optional;

/** 请求范围内的服务端用户主体；异步消费者不设置该上下文，因此仍可按 ID 处理任务。 */
public final class AuthUserContext {
    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    private AuthUserContext() {
    }

    public static void set(String userId) {
        if (userId == null || userId.isBlank()) {
            CURRENT_USER.remove();
        } else {
            CURRENT_USER.set(userId.trim());
        }
    }

    public static Optional<String> currentUserId() {
        return Optional.ofNullable(CURRENT_USER.get()).filter(value -> !value.isBlank());
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
