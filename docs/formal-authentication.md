# 正式登录认证

当前版本已接入基于 Spring Security + Redis 的账号登录认证，登录 Cookie 只保存随机会话标识，用户主体和会话 TTL 保存在 Redis 中；密码使用 BCrypt 哈希保存，不保存明文密码。

## 已完成能力

- 邮箱注册、登录、退出和当前用户查询：`/api/auth/register`、`/api/auth/login`、`/api/auth/logout`、`/api/auth/me`。
- 服务端会话 Cookie：默认名称 `INTERVIEW_AUTH`，默认 7 天滑动过期，支持 `HttpOnly`、`SameSite` 和 `Secure` 配置。
- 正式模式下，未登录访问业务 API 返回 401；写请求启用 CSRF Cookie/Header 校验。
- Agent 会话 owner 使用服务端用户 ID；迁移模式下仍兼容旧的 `X-User-Id`，但一旦存在正式登录会话，服务端会优先使用会话身份，不能被 Header 覆盖。
- 简历和面试会话增加 `owner_id`，列表、详情、编辑、删除、导出、未完成恢复和 Agent 上下文均按当前用户过滤；Redis 中的面试缓存命中前也会进行数据库归属校验。
- 首个账号登录或注册时，如果数据库仍只有一个账号且核心数据尚未完成归属，系统会把迁移前的无主简历和面试会话归到该账号；一旦进入多账号状态，不会自动替其他账号认领数据。
- 前端启动时恢复 `/api/auth/me`，未登录会进入登录页；退出后清理本地状态并回到登录页。

## 配置

```dotenv
# 正式环境必须开启
APP_AUTH_REQUIRED=true

# HTTPS 环境建议开启；HTTP 测试环境保持 false
APP_AUTH_SECURE_COOKIE=true

# 可选配置
APP_AUTH_COOKIE_NAME=INTERVIEW_AUTH
APP_AUTH_SESSION_TTL=7d
APP_AUTH_COOKIE_SAME_SITE=Lax
```

`APP_AUTH_REQUIRED` 默认是 `false`，用于已有单用户/匿名测试数据的平滑迁移。部署正式环境时必须显式设置为 `true`，并在 HTTPS 下设置 `APP_AUTH_SECURE_COOKIE=true`。

## 当前边界

简历、面试会话和 Agent 对话已经按账号隔离。知识库当前仍定位为部署级共享参考资料：知识库管理接口和 RAG 内容不按个人账号分区，适合团队共用技术题库；如果后续需要个人私有知识库，需要再为知识库及向量文档增加 `owner_id` 和迁移策略。

正式多用户环境还应补充邮箱验证、密码找回、管理员账号治理和审计日志；这些不影响当前登录、会话失效和核心面试数据隔离。

## 验收

```powershell
pwsh -NoProfile -File .\scripts\auth-acceptance.ps1 -BaseUrl http://localhost:18083
```

验收覆盖：未登录 401、注册、重复注册、登录 Cookie、会话恢复、CSRF、旧 Header 防伪、退出和退出后的会话失效。
