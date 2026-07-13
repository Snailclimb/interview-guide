# Agent 化改造开发进度

> 目标来源：`goal.md`
>
> 技术规范：`docs/agent-runtime-technical-spec.md`
>
> 本文件只记录实施进度、验证证据与下一步，不修改或重复定义目标。

## 当前迭代

- 阶段：G0 数据库迁移基础与 G1 Agent Run 创建均已完成
- 状态：准备进入 G2——执行账本、事件与恢复基础
- 当前 frontier：GitHub Issue `#3`——让 Agent Run 生命周期成为可补读的 Agent Step
- 本轮审查基点：`063d5f725007ebd039e55fd7571884cd11337821`
- 测试缝：版本化迁移启动、重复迁移、Agent Run API、LEGACY 全量回归

## 本轮范围

- [x] 引入 Flyway 与 PostgreSQL 数据库模块
- [x] 使用 V1 迁移创建现有 `agent_runs` 结构和幂等唯一索引
- [x] 验证重复迁移不重放且保留已有 Agent Run
- [x] `app.agent.enabled` 默认关闭，关闭时拒绝创建新 Run
- [x] 生产 profile 使用 `ddl-auto: validate` 并关闭 pgvector 自动建表
- [x] 保持现有 Agent Run 创建、查询、幂等与并发竞争契约
- [x] 默认关闭状态下保持 LEGACY 文本与语音流程回归通过
- [x] 运行聚焦测试与完整后端测试
- [x] 完成 Standards 与 Spec 双轴代码审查并清零发现

## 实施记录

### 2026-07-13 — G0 数据库迁移基础

- 已将 Agent 表的 Schema 所有权交给 Flyway，现有非空 Schema 以版本 0 建立基线后仍执行 V1。
- 本地开发继续允许 Hibernate 更新既有非 Agent 表；生产 profile 只验证 Schema，不自动改表。
- Agent 开关默认关闭；Agent API 契约测试显式启用，LEGACY 全量回归使用默认关闭配置。
- 未修改工作区中用户已有的语音服务与教学资料改动。

### 2026-07-13 — G1 Agent Run 创建

- 已确认 G1 不包含 LLM、工具、Step、Checkpoint、事件流、语音和前端轨迹。
- 已确认 API 是首要测试缝；只有持久化唯一性使用 Repository 测试补充证明。
- 已记录工作区存在与本迭代无关的语音服务及教学资料改动，本轮不修改这些文件。

## 验证记录

### G0 数据库迁移基础

- Red：`AgentRunRepositoryTest` 改为 `ddl-auto=validate` 且启用 Flyway 后，全新 H2 因缺少
  `agent_runs` 触发 `SchemaManagementException`，证明原实现依赖 Hibernate 建表。
- Green：加入 Flyway、PostgreSQL 模块和 V1 后，迁移启动、幂等唯一性与重复迁移保留数据测试通过。
- 开关验证：关闭 Agent 时创建 API 返回业务码 `12003` 且不写入 Repository；显式启用时
  G1 创建、查询和幂等契约继续通过。
- 聚焦验证：迁移持久化测试与 Agent Run API 契约共 8 条测试通过，耗时 17 秒。
- 完整验证：`./gradlew :app:test --no-daemon` 通过，耗时 1 分 46 秒。

### G1 Agent Run 创建

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
- Red 6：模拟并发唯一约束竞争时，API 返回系统错误而不是赢家 Run。
- Green 6：插入使用独立事务；竞争失败后重读赢家 Run，并复用请求指纹冲突规则。
- 聚焦验证：Agent Run API 5 条测试与 H2 持久化 1 条测试全部通过。
- 完整验证：`./gradlew :app:test --no-daemon` 通过，耗时 2 分 3 秒。
- 审查修复后完整验证：`./gradlew :app:test --no-daemon` 再次通过，耗时 2 分 4 秒。

## 审查记录

### G0 数据库迁移基础

- Standards：未发现仓库标准违反或新增代码气味。
- Spec 初审：发现生产仍可能自动改表、测试 profile 全局开启 Agent 两项 P1。
- Spec 修复：新增生产 profile 的 Schema 验证和 pgvector 禁止自动建表配置；移除测试 profile
  的 Agent 全局启用，让 LEGACY 回归继承默认关闭配置。复审无剩余发现。

### G1 Agent Run 创建

- Standards：已将 JUnit `assertEquals` 改为 AssertJ，并将测试命名从“集成测试”修正为
  “API 契约测试”。单次 Entity 到 Response 映射暂不引入 MapStruct；出现第二个映射点时再提取。
- Spec：已修复并发幂等竞争窗口。
- 延后到后续阶段：`InterviewSession` 的 `LEGACY | AGENT` 模式及会话侧 `agentRunId` 属于 G4
  双模式接入，不在 G1 提前修改现有面试流程。
- 下一基础任务：建立正式数据库迁移机制并迁移 `agent_runs`，生产环境不得依赖
  `ddl-auto`；该项作为 G0 未完成项，不把当前 H2 自动建表误记为生产完成。

## 下一步

1. 领取 GitHub Issue `#3`，让暂停、取消和状态变化形成不可变 Agent Step。
2. 通过 Run 查询与事件查询 API 验证按 `stepSequence` 补读脱敏持久化事件。
