# 正式登录认证

当前版本已接入基于 Spring Security + Redis 的账号登录认证，登录 Cookie 只保存随机会话标识，用户主体和会话 TTL 保存在 Redis 中；密码使用 BCrypt 哈希保存，不保存明文密码。

## 已完成能力

- 邮箱注册、登录、退出和当前用户查询：`/api/auth/register`、`/api/auth/login`、`/api/auth/logout`、`/api/auth/me`。
- 服务端会话 Cookie：默认名称 `INTERVIEW_AUTH`，默认 7 天滑动过期，支持 `HttpOnly`、`SameSite` 和 `Secure` 配置。
- 正式模式下，未登录访问业务 API 返回 401；写请求启用 CSRF Cookie/Header 校验。
- Agent 会话 owner 使用服务端用户 ID；迁移模式下仍兼容旧的 `X-User-Id`，但一旦存在正式登录会话，服务端会优先使用会话身份，不能被 Header 覆盖。
- 简历、普通面试会话、语音面试会话和面试日程均按当前用户过滤；列表、详情、编辑、删除、导出、未完成恢复和 Agent 上下文不会跨账号读取。语音面试的 WebSocket 握手也会校验会话归属，Redis 命中前会再次验证用户主体。
- 首个账号登录或注册时，如果数据库仍只有一个账号且核心数据尚未完成归属，系统会把迁移前的无主简历和面试会话归到该账号；一旦进入多账号状态，不会自动替其他账号认领数据。
- 前端启动时恢复 `/api/auth/me`，未登录会进入登录页；退出后清理本地状态并回到登录页。
- 可选邮箱验证：开启后注册账号不会创建登录会话，验证链接一次性保存在 Redis 中，验证成功后才能登录。
- 可选密码找回：登录页提供找回入口，邮箱存在性不会通过接口响应泄露；重置密码后会使该账号已有会话全部失效。
- 管理员治理：通过 `APP_AUTH_ADMIN_EMAILS` 引导首批管理员，管理员可管理账号状态/角色并查看低敏安全审计；管理员接口由服务端 `ROLE_ADMIN` 强制保护。

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

# 可选：开启后注册必须完成邮箱验证；需要同时配置 SMTP
APP_AUTH_REQUIRE_EMAIL_VERIFICATION=false
APP_AUTH_MAIL_ENABLED=false
APP_AUTH_ADMIN_EMAILS=admin@example.com
APP_AUTH_MAIL_FROM=no-reply@example.com
APP_AUTH_MAIL_FRONTEND_BASE_URL=https://your-domain.example
MAIL_HOST=smtp.example.com
MAIL_PORT=587
MAIL_USERNAME=...
MAIL_PASSWORD=...
MAIL_SMTP_AUTH=true
MAIL_SMTP_STARTTLS=true
```

`APP_AUTH_REQUIRED` 默认是 `false`，用于已有单用户/匿名测试数据的平滑迁移。部署正式环境时必须显式设置为 `true`，并在 HTTPS 下设置 `APP_AUTH_SECURE_COOKIE=true`。
邮箱能力默认关闭，不会改变现有注册和登录流程；只有在 SMTP 配置完成后才应开启
`APP_AUTH_REQUIRE_EMAIL_VERIFICATION`。验证和重置 token 只保存 SHA-256 摘要，原始 token 仅出现在邮件链接中，并按 TTL 一次性消费。
邮箱关闭时会同时关闭 Spring Mail 健康探针，避免空 SMTP 配置影响整体健康状态；开启邮箱后，健康检查会反映 SMTP 连通性。

## 当前边界

简历、普通/语音面试会话、面试日程和 Agent 对话已经按账号隔离。知识库现在支持兼容式 owner 隔离：登录用户新上传的知识库和新建 RAG 会话写入 `owner_id`，列表、详情、下载、查询、题目管理、重向量化和删除均按账号校验；迁移前 `owner_id IS NULL` 的知识库保留为部署级共享参考资料，继续对所有账号可见，避免升级后现有题库失效。当前仍未做知识库协作成员、细粒度分享和管理员治理。

完整业务级审计仍未完成；当前管理员审计只记录账号治理动作，不保存简历、面试回答、Prompt 或密钥内容。邮箱验证和密码找回已经接入，但需由部署环境提供 SMTP 才能进行真实邮件验收。

## 验收

```powershell
pwsh -NoProfile -File .\scripts\auth-acceptance.ps1 -BaseUrl http://localhost:18083

# 账号隔离：简历、语音面试和面试日程
pwsh -NoProfile -File .\scripts\auth-voice-schedule-acceptance.ps1 -BaseUrl http://localhost:18083

# 正式登录下的 Agent 会话、Loop、持久化和模型流式验收
pwsh -NoProfile -File .\scripts\formal-agent-acceptance.ps1 -BaseUrl http://localhost:18083 -CreateInterview -RunModelTests

# 应用重启后的 Agent 任务恢复（需要手动重启 API 容器）
pwsh -NoProfile -File .\scripts\agent-restart-acceptance.ps1 -BaseUrl http://localhost:18083 -StatePath $env:TEMP\agent-recovery-state.json
# 停止并重新启动 API，等待超过 30 秒后：
pwsh -NoProfile -File .\scripts\agent-restart-acceptance.ps1 -BaseUrl http://localhost:18083 -VerifyStatePath $env:TEMP\agent-recovery-state.json -Cleanup
```

验收覆盖：未登录 401、注册、重复注册、登录 Cookie、会话恢复、CSRF、旧 Header 防伪、退出和退出后的会话失效；以及不同账号之间的简历、语音面试、面试日程和 Agent 会话隔离。

`-RunModelTests` 会发起真实模型流式请求；如果只想验证登录、会话归属和 Agent 数据生命周期，可以去掉该参数。脚本会清理本轮创建的临时面试记录。
