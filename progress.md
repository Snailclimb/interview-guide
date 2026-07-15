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
| #3 | G2 基础 | 本地验收完成，待发布 | #2 | 远端关闭须在实现分支发布后进行 |
| #4 | G2 基础 | 本地契约验收完成，待发布 | #3 | 数据/API 幂等契约已验证；端到端题目生产者依赖 #6 |
| #5 | G2 恢复 | 未开始 | #4 | 等待 Answer Message 幂等契约 |
| #6 | G3 | 未开始 | #5 | 等待恢复基础 |
| #7、#8 | G3 | 未开始 | #6 | 可在 #6 后按互不冲突边界并行审查/实现 |
| #9 | G3 治理 | 未开始 | #7、#8 | 等待工具循环和预算基础 |
| #10、#11 | G3/G4 | 未开始 | #9 | 等待授权治理基础 |
| #12 | G4 上下文 | 未开始 | #7、#11 | 等待工具和资源授权 |
| #13 → #17 | G4/G5 | 未开始 | 见 Issue 的串行依赖 | 不提前实现 |

## 当前迭代

- 阶段：G0 数据库迁移基础与 G1 Agent Run 创建已完成；G2 的 `#3` 本地验收完成
- 状态：当前 HEAD 含 `#3` 实现提交 `80a5193` 和记录提交 `41e6c4d`；远端 Issue 将在分支发布后关闭
- 当前 frontier：GitHub Issue `#4`——幂等 Answer Message 契约已本地验收，待发布后关闭；下一开发
  依赖为 #5
- 本轮审查基点：`063d5f725007ebd039e55fd7571884cd11337821`
- 测试缝：版本化迁移启动、重复迁移、Agent Run API、LEGACY 全量回归

## Ticket 记录

### #3 — 让 Agent Run 生命周期成为可补读的 Agent Step（G2 基础）

- 状态：本地验收完成；实现提交为 `80a5193`、记录提交为 `41e6c4d`，前置 `#2` 已关闭；远端
  Issue 尚未关闭，因为实现分支尚未发布。
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

### #4 — 幂等接受当前问题的 Answer Message（G2 基础）

- 状态：本地契约验收完成，待发布；不得将此状态误报为端到端可用。`current_question_id` 已是
  持久化权威，但只有 #6 会生产 `WAITING_USER + currentQuestionId`，因此当前成功路径由持久化
  fixture 验证。
- 需求：用户提交针对当前问题的回答时，以稳定 `messageId` 持久化不可变 Answer Message；重复投递
  返回原处理结果，不同并发消息、过期问题回答和不可回答状态被明确拒绝且不排队。
- 约束：
  - 保持 #4 的纵向范围：不实现 LLM、工具、Checkpoint、恢复循环或前端 Workspace；
  - `runId + messageId` 必须由数据库唯一约束保护；状态推进使用乐观锁或带前置状态的条件更新；
  - 请求/响应使用不可变 record 与 `Result<T>`，业务失败使用 `BusinessException`；Answer Message 与
    其内容不可变，且不保存隐藏推理；
  - 一个 Run 同时最多一个在途 Turn；`RUNNING` 时的新消息不可悄悄进入队列；
  - 第一阶段不引入 Agent 专用 Redis Stream、分布式锁或外部调用，LEGACY 行为不能改变。
- 验收条件：
  - Answer Message 含稳定 `messageId` 并关联被回答的问题；
  - 同一 `runId + messageId` 的重试不产生第二个 Turn；
  - `RUNNING` 时不同新消息被拒绝且不保存为待处理队列；
  - 过期问题、未恢复的 `PAUSED` Run 和终态 Run 返回明确业务错误。
- 预先确认的测试缝：`POST /api/agent/runs/{runId}/messages` 是主要外部契约；H2 Repository 测试仅
  证明 `(runId, messageId)` 唯一性和必要并发语义。这与 `goal.md` 的“API 优先、持久化约束补充”
  测试策略一致。
- Agent 分工：设计审查 Agent 先提出当前问题权威、最小表结构和 API 响应方案；实现 Agent 按
  Red → Green 的单一纵向切片落地；验证/审查 Agent 独立复核测试、约束和文档；协调 Agent 维护
  本条目与 `exp.md`。
- 设计结论：
  - V3 新增不可变 `agent_answer_messages`，以 `(run_id, message_id)` 唯一约束、请求指纹和接收
    时间保存消息；同一键而载荷不同必须返回专用幂等冲突，响应不回显回答正文；
  - `agent_runs.current_question_id` 是 `WAITING_USER` 时唯一的当前问题权威，且由数据库约束要求
    非空；不复用 LEGACY `currentQuestionIndex`，不从当前仅含状态的 Step 推导。原因和未来 #6 的
    唯一生产者责任见 ADR-0012；
  - 首次接受使用条件状态推进 `WAITING_USER -> RUNNING`，同一事务写 Answer Message 和状态 Step；
    相同消息先查并复用原响应，条件推进未命中后重读已提交的胜者；
  - 公开响应只含 `runId`、`messageId`、`answeredQuestionId`、`receivedAt` 与首次接受状态；不引入
    尚无持久化模型的 `turnId`。
- TDD 切片：
  1. Red：等待用户的 Run 提交当前问题回答没有路由/响应；Green：接受并进入 `RUNNING`，保存一条
     消息与一条状态 Step。
  2. Red：相同 `messageId` 重试产生第二次推进；Green：返回同一稳定响应且只保留一条消息/Step。
  3. Red：同一 ID 不同载荷被静默复用；Green：返回专用幂等冲突且状态不变。
  4. Red：`RUNNING` 时新消息仍被保存；Green：返回明确 busy 错误且绝不排队。
  5. Red：过期 `questionId` 或 `PAUSED`/终态 Run 的回答被接受；Green：返回明确业务错误且无副作用。
  6. H2 补充：V3 迁移、`(runId,messageId)` 唯一性、Run 外键、等待态问题指针约束以及条件推进仅一方
     成功。
  7. Red：非等待态残留当前问题标识或取消等待态 Run 未清理指针；Green：数据库双向约束与状态迁移
     清理指针。
- 实施记录：
  - Red 1：新增 `acceptsAnswerForCurrentQuestion` API 契约测试后执行
    `./gradlew.bat :app:test --no-daemon --tests "...AgentRunApiContractTest.acceptsAnswerForCurrentQuestion"`；
    18 秒失败，`ReflectionTestUtils` 找不到 `currentQuestionId`（测试第 437 行），证明现有持久化/
    API 契约无法表达“当前问题”。
  - Green 1：新增 V3 的 `current_question_id`、`agent_answer_messages`、Run 外键及
    `(run_id, message_id)` 唯一约束；新增不可变消息实体/仓库、提交 DTO/响应/API，使用带状态和
    当前问题前置条件的原子 `WAITING_USER -> RUNNING` 更新，并在同一事务写消息和状态 Step。
  - Green 验证：相同聚焦测试 18 秒 `BUILD SUCCESSFUL`；
    `AgentRunApiContractTest` 与 `AgentRunRepositoryTest` 聚焦回归 19 秒 `BUILD SUCCESSFUL`；
    `git diff --check` 通过。
  - Red 2a：`reusesAcceptedAnswerForSameMessagePayload` 在首条受理后重试命中 `RUNNING` 的泛化拒绝，
    17 秒失败，证明状态检查在幂等记录之前会破坏重试契约。
  - Green 2a：新增 `(runId, messageId)` 查询；`submitAnswer` 在所有 Run 状态判断之前比较已持久化载荷
    指纹，相同则直接复用首次 `AnswerMessageResponse`。
  - Red 2b：`rejectsConflictingPayloadForSameMessageId` 证明相同消息 ID 的不同正文/问题会落入泛化
    `RUNNING` 错误，17 秒失败。
  - Green 2b：新增 `AGENT_ANSWER_MESSAGE_IDEMPOTENCY_CONFLICT(12005)`；指纹不同立即返回
    `BusinessException`，消息为“同一 messageId 对应的 Answer Message 载荷不一致”。
  - Green 验证：强制执行
    `./gradlew.bat :app:test --no-daemon --rerun-tasks --tests "...rejectsConflictingPayloadForSameMessageId" --tests "...reusesAcceptedAnswerForSameMessagePayload"`，
    5 个任务实际执行，27 秒 `BUILD SUCCESSFUL`。
  - Red 3：`rejectsNewAnswerWhenRunIsRunning` 预期不同 `messageId` 返回 12006，聚焦测试在
    `AgentRunApiContractTest.java:290` 失败，证明新消息仍落入泛化状态错误。
  - Green 3：新增 `AGENT_RUN_BUSY(12006, "Agent Run 正在执行，暂不接受新的回答")`；在消息幂等
    检查之后、通用 `WAITING_USER`/当前问题校验之前，`RUNNING` 直接返回该专用业务错误。
  - Green 验证：
    `./gradlew.bat :app:test --no-daemon --tests "...reusesAcceptedAnswerForSameMessagePayload" --tests "...rejectsConflictingPayloadForSameMessageId" --tests "...rejectsNewAnswerWhenRunIsRunning"`
    三项通过，`git diff --check` 通过。
  - Red 4：新增过期题目测试后，旧实现返回泛化 12004，而契约要求不泄露当前题目标识的专用错误 12007。
  - Green 4：新增 `AGENT_ANSWER_MESSAGE_NOT_CURRENT_QUESTION(12007, "该回答对应的问题已过期，请回答当前问题")`；
    在幂等和 `RUNNING` 检查之后、通用状态检查之前，`WAITING_USER` 的非当前问题立即拒绝。
  - Green 验证：`rejectsAnswerForStaleQuestionWithoutPersistingAnything` 通过；包含接受、重试、冲突、
    busy 和 stale 的五个回答链路 API 测试通过。
  - Red 5：新增参数化 API 测试覆盖 `PAUSED`、`COMPLETED`、`FAILED`、`CANCELLED`，旧实现四例均
    返回泛化错误消息，未能表达“先恢复”与终态不可继续的行为。
  - Green 5：在既有幂等/busy/stale 守卫之后补充明确的 12004 业务拒绝：暂停态提示先恢复，完成、
    失败和取消态分别说明不能再提交回答；四例均断言不新增消息/Step 且 Run 状态不变。
  - Green 验证：新增参数化测试 4 例通过，17 秒 `BUILD SUCCESSFUL`。
  - 持久化验证（事后补强）：在 `AgentRunRepositoryTest` 新增真实 H2/Flyway 测试，证明同一
    `(runId,messageId)` 仅能保存一条 Answer Message、消息 `run_id` 必须引用存在的 Run、
    `WAITING_USER` 不得缺 `currentQuestionId`，且条件推进只有首次返回 1、第二次返回 0 并清空
    指针、进入 `RUNNING`。`AgentRunRepositoryTest` 7 个测试通过、0 failures/0 errors。
  - Red 6：模拟同一 `(runId,messageId)` 的竞争请求时，首次消息查询为空、条件推进返回 0、随后
    查询读到另一事务已持久化的获胜消息；旧实现返回 12004 而不是首次受理结果，聚焦测试按预期
    `BUILD FAILED`。
  - Green 6：条件推进未命中后重新查询 `(runId,messageId)`；相同载荷复用获胜消息的原响应，载荷
    不同仍返回 12005。若没有获胜消息，则重新读取 Run 并沿用 busy、stale 或通用拒绝语义；该路径
    不保存额外 Answer Message 或 Step。
  - Green 验证：`reusesWinningAnswerAfterLosingConditionalAdvance` 聚焦测试和整个
    `AgentRunApiContractTest` 均 `BUILD SUCCESSFUL`；随后强制执行
    `./gradlew.bat :app:test --no-daemon --rerun-tasks --tests "interview.guide.modules.agentinterview.AgentRunApiContractTest" --tests "interview.guide.infrastructure.agent.persistence.AgentRunRepositoryTest"`，
    5 个任务实际执行、38 秒 `BUILD SUCCESSFUL`。
  - Red 7（Spec 审查反馈）：新增“非 `WAITING_USER` 不得保留 `currentQuestionId`”和“取消等待态必须清空
    指针”的 H2 测试；旧 V3 只校验等待态非空、旧 `cancel()` 未清空指针，两个测试在 22 秒内按预期
    `BUILD FAILED`。
  - Green 7：将 V3 CHECK 收紧为 `currentQuestionId` 仅在 `WAITING_USER` 时非空，并在 `cancel()` 进入
    `CANCELLED` 前清空指针；两个聚焦测试 18 秒 `BUILD SUCCESSFUL`。
  - 最终完整验证：2026-07-15 执行 `./gradlew.bat :app:test --no-daemon --rerun-tasks`，5 个任务实际
    执行、1 分 59 秒 `BUILD SUCCESSFUL`。
- 本切片踩坑：#4 在 #6 前没有题目生产者，不能为测试增加隐藏的“设置当前题目”生产 API。测试使用
  持久化 fixture 构造 `WAITING_USER + currentQuestionId` Run，生产代码只消费该指针；该边界由
  ADR-0012 固化。
- 本切片踩坑：幂等检查必须先于状态校验；否则成功后的消息在 Run 已转为 `RUNNING` 时会被自己的
  重试错误拒绝。不同载荷不能复用通用 Run 创建幂等错误，因为冲突边界是 Answer Message。
- 本切片踩坑：不能用通用“不接受回答”错误掩盖 `RUNNING` 的单在途规则；客户端需要能区分“重试原
  消息”与“当前 Turn 仍在执行、不得排队新消息”。
- 本切片踩坑：过期问题错误只能说明“不是当前问题”，不能回显服务器保存的 `currentQuestionId`；
  否则客户端可据此推断或混淆当前面试状态。
- 本切片踩坑：统一错误码不代表统一错误语义；`PAUSED` 是可恢复状态，而完成、失败和取消是终态，
  API 文案必须让客户端知道后续能否继续提交，而不能仅返回“当前不接受”。
- 本切片踩坑：V3 迁移和条件更新在持久化测试补齐前已经存在，不能补写或伪造 Red 历史；该验证按
  “事后补强”记录，仍以真实 H2/Flyway 结果证明数据库不变量。
- 本切片踩坑：条件状态更新只保证一名请求能推进 Run，不能单独满足同一消息的并发幂等契约；失败者
  必须在条件更新未命中后重读已提交的获胜消息并核对载荷，否则客户端会把网络重试误判为状态错误。
- 已知边界：当前问题已有 Run 级持久化权威，但尚无生产者；#6 才能将 Run 置为 `WAITING_USER` 并
  发布/更新该指针。为保持纵向边界，#4 不新增隐藏的“设置当前题目”生产 API，也不提前实现模型出题
  或 Runtime 状态。
- Red/Green 记录：已完成“接受当前问题回答”“幂等重放/冲突”“RUNNING 新消息拒绝”“过期问题
  拒绝”“暂停/终态拒绝”和“并发胜者重读”六个 API 切片，并完成 H2 持久化约束补强；待全量回归和
  Standards/Spec 双轴审查后标记本地验收完成。

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

### G2 #4 幂等 Answer Message

- Standards 初审：发现一处测试行宽接近规则上限，以及名为专用推进却暴露任意状态参数的 Repository
  方法。已拆分测试过滤链，并将条件更新收紧为固定 `WAITING_USER -> RUNNING` 的 Repository native
  `@Query`；复审无剩余 Standards 发现。
- Spec 初审：发现 `currentQuestionId` 仅被单向约束，可能在非等待态残留，且进度文档将“没有生产者”
  错写为“没有持久化权威”。已将 V3 CHECK 改为双向不变量、取消时清空指针，并以 H2 Red/Green 覆盖；
  Spec 复审无阻塞或范围蔓延。
- 延后到 #6：#4 完成 Answer Message 数据/API 幂等契约，但 #6 才是 `WAITING_USER + currentQuestionId`
  的唯一生产者；在此之前不得把 fixture 覆盖误报为用户端到端可用。

## 下一步

1. 保持 #3、#4 的远端 Issue 为 OPEN，直到本地实现分支发布；发布后分别以对应条目的验收证据关闭。
2. 在 #4 发布/关闭后按依赖启动 #5；开工前先在本文件写入需求、约束、验收条件和 Agent 分工。
3. 继续保持 #6 是当前问题生产者的边界，不得通过新增隐藏 API 或修改 LEGACY 题单绕过依赖。
