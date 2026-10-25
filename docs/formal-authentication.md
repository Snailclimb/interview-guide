# 正式登录认证（第一阶段）

当前版本已接入基于 Spring Security + Redis 的账号登录认证，登录 Cookie 只保存随机会话标识，用户主体和会话 TTL 保存在 Redis 中；密码使用 BCrypt 哈希保存，不保存明文密码。

## 已完成能力

- 邮箱注册、登录、退出和当前用户查询：`/api/auth/register`、`/api/auth/login`、`/api/auth/logout`、`/api/auth/me`。
- 服务端会话 Cookie：默认名称 `INTERVIEW_AUTH`，默认 7 天滑动过期，支持 `HttpOnly`、`SameSite` 和 `Secure` 配置。
- 正式模式下，未登录访问业务 API 返回 401；写请求启用 CSRF Cookie/Header 校验。
- Agent 会话 owner 使用服务端用户 ID；迁移模式下仍兼容旧的 `X-User-Id`，但一旦存在正式登录会话，服务端会优先使用会话身份，不能被 Header 覆盖。
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

这是第一阶段认证接入。Agent 对话线程已经按用户隔离；面试报告、简历、知识库等历史业务表目前仍是旧的单租户数据模型，尚未全部增加 `owner_id` 并完成数据迁移。因此当前适合单用户或可信私有部署，不应直接作为多用户生产环境使用。

下一阶段需要为简历、面试会话、知识库和相关异步任务补充用户所有权、查询过滤、跨用户访问拒绝，以及旧数据的明确归属迁移策略。完成这一步后，才能把认证和完整的业务数据隔离一起作为多用户生产能力发布。

## 验收

```powershell
pwsh -NoProfile -File .\scripts\auth-acceptance.ps1 -BaseUrl http://localhost:18083
```

验收覆盖：未登录 401、注册、重复注册、登录 Cookie、会话恢复、CSRF、旧 Header 防伪、退出和退出后的会话失效。
