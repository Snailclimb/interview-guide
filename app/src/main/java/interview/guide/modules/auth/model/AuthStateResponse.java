package interview.guide.modules.auth.model;

/** 前端启动时判断是否需要登录以及当前用户是谁。 */
public record AuthStateResponse(
    boolean required,
    boolean authenticated,
    AuthUserResponse user
) {
}
