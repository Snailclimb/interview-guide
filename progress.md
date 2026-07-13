# Agent 化改造开发进度

> 目标来源：`goal.md`
>
> 技术规范：`docs/agent-runtime-technical-spec.md`
>
> 本文件只记录实施进度、验证证据与下一步，不修改或重复定义目标。

## 当前迭代

- 阶段：G1——可幂等创建 Agent Run
- 状态：进行中
- 审查基点：`3cb3dae872cc7a9eb08be75097fde243994307a0`
- 测试缝：`POST /api/agent/runs`、`GET /api/agent/runs/{runId}`

## 本轮范围

- [x] 创建 Run 返回非空 `runId`、`CREATED` 与 `businessSessionId`
- [x] 可通过 `runId` 查询同一 Run
- [x] 相同 `Idempotency-Key` 与相同请求返回同一 `runId`
- [x] 相同 `Idempotency-Key` 与不同请求返回统一业务错误
- [x] H2 持久化验证同一幂等请求只有一条 Run
- [x] 运行聚焦测试与完整后端测试
- [ ] 对照项目规范和 `goal.md` 完成代码审查

## 实施记录

### 2026-07-13

- 已确认 G1 不包含 LLM、工具、Step、Checkpoint、事件流、语音和前端轨迹。
- 已确认 API 是首要测试缝；只有持久化唯一性使用 Repository 测试补充证明。
- 已记录工作区存在与本迭代无关的语音服务及教学资料改动，本轮不修改这些文件。

## 验证记录

- Red 1：`AgentRunApiIntegrationTest` 编译失败，缺少 Agent Run 的 Repository、Service 与
  Controller；失败原因与首条尚未实现的行为一致。
- Green 1：POST 创建契约通过，返回非空 `runId`、`CREATED` 与业务会话关联。
- Red 2：创建后的 GET 请求未找到路由，查询契约按预期失败。
- Green 2：GET 查询契约通过，可读取同一 Run 的状态与业务会话关联。
- Red 3：相同 `Idempotency-Key` 重试产生了两个不同 UUID，幂等契约按预期失败。
- Green 3：相同幂等键与相同请求复用已有 Run，返回原 `runId`。
- Red 4：同一幂等键携带不同业务会话时仍返回旧 Run，冲突契约按预期失败。
- Green 4：请求指纹不一致时返回 `12002` 统一业务错误。
- Red 5：移除表约束后，H2 可写入两条相同幂等键的 Run，持久化唯一性契约按预期失败。
- Green 5：`agent_runs.idempotency_key` 唯一约束生效，H2 拒绝第二条重复记录。
- 聚焦验证：Agent Run API 4 条测试与 H2 持久化 1 条测试全部通过。
- 完整验证：`./gradlew :app:test --no-daemon` 通过，耗时 2 分 3 秒。

## 下一步

1. 对照项目规范和 `goal.md` 完成代码审查。
2. 处理审查发现并复测。
3. 仅提交本轮 Agent Run 与进度文件。
