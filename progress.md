# Agent 化改造开发进度

> 目标来源：`goal.md`
>
> 技术规范：`docs/agent-runtime-technical-spec.md`
>
> 本文件只记录实施进度、验证证据与下一步，不修改或重复定义目标。
>
> **记录规则：每个开始实施的 GitHub ticket 必须先在本文件建立条目，且在关闭前补全
> 需求、约束、验收条件、Agent 分工、实现/验证证据和踩坑。可复用经验只沉淀到
> [`exp.md`](exp.md)，不要仅留在单个 ticket 的过程记录里。**

## Agent 化开发协议

每个 ticket 依照以下顺序推进；任何一步未满足时，ticket 保持进行中，不能提前领取依赖它的
后续 ticket。

1. 协调 Agent 从 GitHub Issue、`goal.md` 与技术规范提取需求、约束、验收条件和依赖，并在
   本文件创建 ticket 条目。
2. 审查 Agent 独立核对范围和现有代码，标出与未提交用户修改的边界。
3. 实现 Agent 仅在该 ticket 范围内以测试缝推进；每次 Red/Green、设计取舍和异常都写回条目。
4. 验证 Agent 运行聚焦测试、持久化/迁移验证及必要的完整回归；协调 Agent 再做 Standards 与
   Spec 双轴审查。
5. 只有验收证据、审查结论和踩坑均已写入后，才关闭 Issue、解锁下一依赖，并将通用结论写入
   `exp.md`。

## Ticket 队列与依赖

| Ticket | 阶段 | 状态 | 依赖 | 当前结论 |
| --- | --- | --- | --- | --- |
| #2 | G0 | 已关闭 | 无 | 迁移与 LEGACY 基线已验证 |
| #3 | G2 基础 | 验收中 | #2 | 已有实现提交，待完成双轴审查并同步远端状态 |
| #4 | G2 基础 | 未开始 | #3 | #3 完成后才可领取 |
| #5 | G2 恢复 | 未开始 | #4 | 等待 Answer Message 幂等契约 |
| #6 | G3 | 未开始 | #5 | 等待恢复基础 |
| #7、#8 | G3 | 未开始 | #6 | 可在 #6 后按互不冲突边界并行审查/实现 |
| #9 | G3 治理 | 未开始 | #7、#8 | 等待工具循环和预算基础 |
| #10、#11 | G3/G4 | 未开始 | #9 | 等待授权治理基础 |
| #12 | G4 上下文 | 未开始 | #7、#11 | 等待工具和资源授权 |
| #13 → #17 | G4/G5 | 未开始 | 见 Issue 的串行依赖 | 不提前实现 |

## 当前迭代

- 阶段：G0 数据库迁移基础与 G1 Agent Run 创建已完成；G2 从 `#3` 进入验收收尾
- 状态：当前 HEAD 已包含 `#3` 实现提交 `80a5193`，远端 Issue 仍为 OPEN，不能仅以提交认定完成
- 当前 frontier：GitHub Issue `#3`——让 Agent Run 生命周期成为可补读的 Agent Step
- 本轮审查基点：`063d5f725007ebd039e55fd7571884cd11337821`
- 测试缝：版本化迁移启动、重复迁移、Agent Run API、LEGACY 全量回归

## Ticket 记录

### #3 — 让 Agent Run 生命周期成为可补读的 Agent Step（G2 基础）

- 状态：验收中；实现提交为 `80a5193`，前置 `#2` 已关闭，远端 Issue 尚未关闭。
- 需求：暂停或取消 Agent Run 后可查询一致状态；每个成功生命周期变化成为带 Run 内单调序号的
  不可变 Step；事件可按序补读。
- 约束：Controller 只委托 Service；响应必须为 `Result<T>` 且不得返回 Entity、隐藏思维链或
  未脱敏执行数据；Step 由 PostgreSQL/Flyway 持久化，不新增独立事件表、Outbox 或 Agent Redis
  队列；终态 Interview Session 不得被 Run 状态变化重新激活。
- 验收条件：
  - 暂停和取消 API 返回统一响应，并拒绝对终态 Session 推进 Run；
  - 每次成功状态变化有唯一 `(runId, stepSequence)` 的不可变 `AgentStep`；
  - `GET /api/agent/runs/{runId}/events?afterSequence=` 按升序返回脱敏事件；
  - API 不泄露 Entity、隐藏推理或原始执行数据。
- Agent 分工：规格/依赖审查、代码与仓库规范审查、实现验收和持久化测试彼此独立；协调 Agent
  汇总证据并维护本条目与 `exp.md`。
- 已实施：V2 Flyway migration、不可变 `AgentStepEntity` 与唯一序号约束、暂停/取消/事件查询
  API，以及 API 契约与 H2 持久化测试。
- 验证证据：
  - `AgentRunApiContractTest` 于 2026-07-14 通过，覆盖暂停、取消、终态拒绝、Step 序号与
    `afterSequence` 补读及脱敏字段；
  - `AgentRunRepositoryTest` 于 2026-07-14 通过，覆盖 Flyway 重复迁移和同一 Run 的 Step 序号唯一性；
  - `git show --check 80a5193` 无空白错误；2026-07-14 强制全量
    `./gradlew :app:test --no-daemon --rerun-tasks` 通过，5 个任务实际执行，耗时 1 分 38 秒。
- 双轴审查：
  - Standards：无发现。Controller 仅委托，业务错误使用 `BusinessException`，事务只在 Service；
    无通配符导入、显著重复代码或新增代码异味。
  - Spec：`#3` 明示的暂停/取消、不可变 Step、序号补读与脱敏响应均有实现和测试。审查指出
    技术规范最终要求取消整个 Interview Session，但当前 Session 尚未有 `CANCELLED` 状态，且
    `#13` 才是 AGENT/LEGACY 会话接入切片；若现在改变既有 Session/Redis 状态机会越过该边界。
    此项转为 `#13` 的必验约束，不能在后续实现中遗漏。
  - 开关语义：G0 开关当前保护新 Run 创建；对历史 Run 的查询、暂停与取消保留可用，以支持安全
    停止和审计。全局灰度关闭/回退策略属于 #17，届时必须明确并覆盖测试。
- 踩坑与处理：发现进度文档与远端状态漂移——代码已经提交但 `progress.md` 仍把 `#3` 写为下一步，
  GitHub Issue 也仍是 OPEN。已将“提交不等于完成、必须有验收和审查证据后才能关闭”的规则纳入本
  文件和 `exp.md`；尚未伪造原始开发过程缺失的 Red/Green 记录。
- 经验沉淀：`EXP-0003`、`EXP-0004`（见 `exp.md`）。

### #2 — 建立版本化数据库迁移并保护 LEGACY 基线（G0，回溯记录）

- 状态：已关闭；相关提交 `e44ac9d`、`55ef6d9`、`3c62cdc`。
- 需求：以正式、可重复迁移创建 Agent Run 数据结构，默认关闭 Agent，同时保持既有面试行为和
  G1 创建/查询/幂等契约。
- 约束：生产环境不能依赖 Hibernate 自动建表；Agent 表 Schema 由 Flyway 管理；保留非 Agent
  工作流和工作区无关修改。
- 验收条件：全新数据库创建结构、重复启动不重放/不破坏已有 Run、默认开关关闭且 LEGACY 回归、
  G1 契约继续通过。
- 已实施与验证：V1 Flyway migration、生产 profile `ddl-auto: validate` 和 Agent 默认开关；
  既有记录证明迁移/幂等/API 聚焦测试及完整 `./gradlew :app:test --no-daemon` 均通过。
- 踩坑与处理：H2 在 `ddl-auto=validate` 下暴露了原先依赖 Hibernate 自动建表；非空 schema 的
  Flyway baseline 与生产自动建表风险均已修正。详见 `EXP-0001`、`EXP-0002`。

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

### G2 #3 生命周期 Step

- API 聚焦验证：`AgentRunApiContractTest` 通过，覆盖暂停、取消、终态 Session 拒绝、按序补读和
  脱敏返回。
- 持久化聚焦验证：`AgentRunRepositoryTest` 通过，覆盖 Flyway V2 重复迁移和 `(runId, stepSequence)`
  唯一约束。
- 全量验证：2026-07-14 执行 `./gradlew :app:test --no-daemon --rerun-tasks`，5 个任务实际执行且
  `BUILD SUCCESSFUL`，耗时 1 分 38 秒；此前一次外部超时留下 Gradle 测试文件句柄，清理完成后
  强制复跑成功。这是环境问题，不是测试断言失败。

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
  `ddl-auto`；该项已由 #2 完成，保留此处仅作历史决策记录。

### G2 #3 生命周期 Step

- Standards：无发现。
- Spec：#3 的书面验收条件已经由 API、Step 迁移和持久化测试覆盖。
- 延后到 #13：技术规范中的“取消整个 Interview Session”需要新增 AGENT 会话模式与会话状态同步；
  在 #13 前不得通过改动 LEGACY 会话状态机来绕过纵向切片边界。
- 延后到 #17：Agent 功能开关关闭后对已存在 Run 的查询/控制策略需要作为灰度与回退契约定义并测试。

## 下一步

1. 关闭已完成且已审查的 GitHub Issue `#3`，使 #4 的依赖解除。
2. 为 GitHub Issue `#4` 建立完整 progress 条目（需求、约束、验收、Agent 分工、预期测试缝）。
3. 仅在 #4 条目就绪后，将其分派给实现 Agent；不得提前实现 #5 及以后的恢复/Runtime 能力。
