# Goal：将 AI 面试平台升级为受治理的 Agent 系统

> 状态：待实施  
> 主要规范：`docs/agent-runtime-technical-spec.md`  
> 架构调研：`docs/agent-transformation-plan.md`

## 1. 目标结果

在保留现有可靠 Workflow 的前提下，为平台增加一个持久化、可恢复、可审计且受预算约束的 Agent Runtime，并以自适应文本面试官作为第一个业务 Agent。

完成后，同一面试配置下，系统能够根据候选人的不同回答，自主选择追问、换题、检索资料、调整难度或结束面试；模型只能调用当前 Run 被授权的工具，所有模型决策、工具调用、状态变化与停止原因均可追踪和恢复。

## 2. 成功标准

项目达到以下条件时，Agent 化目标视为完成：

1. 文本面试支持 `LEGACY` 与 `AGENT` 双模式，默认仍可使用旧模式降级。
2. `AGENT` 模式不再预生成完整题单，每次回答触发一个有界 ReAct Turn。
3. 不同回答能够产生不同的工具序列、追问策略或下一问题。
4. Agent Run、Step 与 Checkpoint 持久化到 PostgreSQL，服务重启后可从最近检查点继续。
5. Run 受最大步骤、工具次数、运行时间、Token 与费用预算约束，不会无限循环。
6. 工具按权限和副作用等级执行，外部副作用必须经过用户批准。
7. 前端能够接收脱敏后的状态、工具进度、来源与停止原因，不显示隐藏思维链。
8. 自动化评估覆盖工具选择、终止、恢复、越权、重复调用和成本。
9. `LEGACY` 模式、简历解析、知识库向量化、统一评估、ASR/TTS 等现有 Workflow 保持可用。

## 3. 已确定的架构决策

### 3.1 Agent 与 Workflow 的边界

- 文件上传、解析、向量化、报告导出、ASR、TTS、限流和可靠重试继续使用确定性 Workflow。
- 只有“下一步做什么”无法预先穷举的部分进入 Agent Runtime。
- 第一业务 Agent 是 `Adaptive Interviewer Agent`，暂不引入多 Agent。

### 3.2 Runtime 技术路线

- 保留 Spring AI 2.0 作为模型与工具调用基础。
- Runtime 使用用户控制的 Tool Execution，逐轮持久化模型与工具执行，不把 `ToolCallingAdvisor` 视为完整 Runtime。
- PostgreSQL 是 Run、Step、Checkpoint 和 Approval 的事实源。
- 第一阶段在当前 Spring Boot 进程内执行有界 ReAct Turn，不使用 Agent 专用 Redis Stream Worker、分布式租约或自动跨实例接管。
- Redis Stream 继续服务现有确定性 Workflow；只有出现多实例或后台接管需求时才重新评估 Agent 分布式执行。
- LLM、S3、MCP 和外部 HTTP 调用均在数据库事务之外执行。

### 3.3 领域边界

- `Interview Session` 是一次面试的业务事实。
- `Agent Run` 是一次可持久化、可恢复的 Agent 执行实例。
- `Agent Turn` 从接受一条当前问题的 Answer Message 开始，到再次等待用户或终止为止；同一 Run 只允许一个在途 Turn。
- `Agent Step` 是带 Run 内单调序号的不可变执行事实。
- `Agent Checkpoint` 是从已提交 Step 派生的可重建恢复快照，不是独立事实源。
- Session 固定资源版本清单并作为选版权威，Run 只能继承；权限在每次读取和模型 Step 前实时检查。
- 不保存或展示模型隐藏思维链，只保存简短决策摘要和脱敏后的执行事实。

### 3.4 工具治理

| 类型 | 示例 | 执行方式 |
|---|---|---|
| `READ_ONLY` | 查询简历、Skill、历史、知识库 | 自动执行 |
| `INTERNAL_WRITE` | 更新能力证据与覆盖图 | 自动执行并审计 |
| `EXTERNAL_SIDE_EFFECT` | 创建日程、发送消息 | 用户批准后执行 |

生产 Agent 不开放任意 Shell、任意文件写入、任意 Web 访问或未经权限策略过滤的 MCP Tool。

每个 Tool Call 使用稳定 `toolCallId`。只读未知结果可重试，内部写入以 `toolCallId` 幂等，外部副作用结果未知时进入 `WAITING_RECONCILIATION` 并由用户核对。工具授权由服务端强制执行，Prompt 和模型输出不能扩大 Run 权限。

## 4. 目标模块

```text
common/agent/
  runtime/         ReAct 循环、状态机、预算和终止策略
  context/         上下文构建与压缩
  tool/            工具注册、权限与审计执行
  event/           运行事件发布
  observability/   Run/Step 观测

infrastructure/agent/
  persistence/     Run、Step、Checkpoint、Approval
  mcp/             后续 MCP 接入

modules/agentinterview/
  controller/      Agent 面试 API
  service/         面试 Agent 业务编排
  repository/      Agent 面试领域持久化
  model/           Request、Response、DTO、Entity
  tool/            面试领域工具
  prompt/          Agent 提示词
  policy/          面试硬规则
```

`LlmProviderRegistry` 继续管理 Provider 和 Model；新增 Agent 专用 Client Factory，根据 Agent 类型、Run 和权限动态提供 Tools 与 Advisors。普通结构化输出继续使用 Plain Client。

## 5. 交付路线

### G0：实施基线

目标：建立 Agent 改造的安全入口，不改变当前用户行为。

交付物：

- `app.agent.enabled` 功能开关，默认关闭。
- 数据库迁移策略与 Agent 表迁移基础。
- Run 状态、事件名、错误语义和 API 命名约定。
- `LEGACY` 保持默认行为的回归测试。

退出条件：

- 功能开关关闭时，现有文本和语音面试行为不变。
- Agent 数据结构能够在测试环境稳定创建。

### G1：第一纵向切片——可幂等创建 Agent Run

目标：让平台第一次拥有可创建、可查询、可重复请求且不重复写入的 Agent 执行实例。

行为契约：

- 当客户端以 `AGENT` 模式启动已有文本面试时，创建一个关联该 Interview Session 的 Agent Run。
- 创建响应返回非空 `runId` 与 `CREATED` 状态。
- 同一逻辑请求使用同一个 `Idempotency-Key` 重试时，返回原来的 `runId`，不新增 Run。
- 同一幂等键携带冲突请求时，返回明确的统一业务错误。
- 可按 `runId` 查询 Run，并得到状态与业务会话关联。

建议 API：

```text
POST /api/agent/runs
GET  /api/agent/runs/{runId}
```

创建请求通过 Header 接收 `Idempotency-Key`，响应继续遵循项目统一的 `Result<T>`。

数据最小集：

- `runId`
- `agentType`
- `businessSessionId`
- `status`
- `idempotencyKey`
- 请求指纹
- `createdAt`、`updatedAt`
- 乐观锁版本

测试顺序：

1. 红：创建 Run 后返回 `runId`、`CREATED` 和会话关联。
2. 绿：实现最小创建与查询路径。
3. 红：相同幂等键重试返回相同 `runId`。
4. 绿：实现幂等记录或唯一约束。
5. 红：相同键与冲突请求返回业务错误。
6. 绿：实现请求指纹校验。

本阶段明确不包含：LLM、工具、Step、Checkpoint、上下文、恢复、事件流、语音和前端轨迹。

退出条件：

- API 集成测试可重复证明创建、查询与幂等行为。
- 持久化层可证明同一幂等请求只存在一条 Run。
- 不需要真实 LLM、Redis 或外部服务即可通过测试。

### G2：执行账本、事件与恢复基础

目标：为后续循环提供每一步可审计、可恢复的执行事实。

交付物：

- Agent Step 与 Agent Checkpoint。
- Run 状态机：`CREATED`、`RUNNING`、`WAITING_USER`、`WAITING_APPROVAL`、`WAITING_RECONCILIATION`、`PAUSED`、`COMPLETED`、`FAILED`、`CANCELLED`。
- 带稳定 `messageId` 和问题关联的 Answer Message 幂等契约；`RUNNING` 时拒绝不同新消息且不排队。
- 基于持久化 Run/Step 的统一事件协议。
- 取消、暂停、恢复和乐观并发控制。
- 脱敏后的 Run/Step 查询接口。

退出条件：

- 每次状态变化都产生持久化 Step 和事件。
- 应用中断后遗留的 `RUNNING` Run 转为 `PAUSED`，并能从最近 Checkpoint 恢复且不重复已提交步骤。
- 并发请求通过数据库条件状态转换或乐观锁竞争，同一 Run 只有一个 Turn 成功推进。

### G3：有界 ReAct Runtime

目标：在不连接真实业务工具的前提下，跑通可终止、可恢复的模型—工具循环。

交付物：

- 进程内 ReAct Loop Executor、状态机、Budget Guard 和 Termination Policy。
- 脚本化 Fake ChatModel。
- 最小只读测试工具。
- 最大步骤、工具次数、运行时间、Token、费用硬预算及总预算内 Closure Reserve。
- 按副作用等级区分的工具异常、未知结果、超时与重试策略。
- Run 级越权计数、Turn 熔断和安全结束策略。

退出条件：

- Fake ChatModel 可稳定复现“直接回答”“调用工具后回答”“重复调用熔断”“预算耗尽”“等待用户”等轨迹。
- 每个模型与工具步骤都能被查询和回放。
- 不依赖真实 Provider 即可验证循环正确性。

### G4：自适应文本面试

目标：用真实业务上下文和只读工具，将文本面试从固定题单升级为受治理的自适应面试。

交付物：

- 文本面试 `LEGACY | AGENT` 双模式。
- 每次回答触发一个有限 ReAct Turn。
- 第一批只读工具：简历画像、面试 Skill、授权知识库、历史面试、剩余预算。
- 内部状态工具：回答证据、能力覆盖、已问主题。
- 面试硬规则：时间、主问题数、阶段、重复主题、安全输出。
- Session 资源版本清单、实时权限检查与固定版本恢复。
- Evidence Store、有限 Evidence Packet、可追溯 Context Summary 和 Prompt 数据边界。
- 复用现有统一评估 Workflow 生成最终报告。

退出条件：

- 不同质量的回答能够产生不同追问或换题路径。
- 达到时间、题数或预算上限时能够正常收尾。
- `LEGACY` 与 `AGENT` 可并存并按开关灰度。
- 未授权简历、知识库或历史会话不可被工具访问。

### G5：可观测性、评估与灰度

目标：证明 Agent 模式比旧 Workflow 更有效且成本可控。

交付物：

- Run、模型、工具、Token、停止原因、循环熔断和恢复指标。
- 标准面试轨迹与脚本化评估集。
- `LEGACY` 与 `AGENT` 对照指标。
- 前端运行状态、能力覆盖、剩余时间和脱敏轨迹。
- 灰度开关、失败降级和运维手册。

退出条件：

- 可量化比较重复问题率、能力覆盖率、追问有效率、完成率、延迟和成本。
- Agent 失败时能够回退到旧模式或安全终止。
- 前端不泄露隐藏推理、密钥或未授权上下文。

### G6：后续扩展

仅在 G1—G5 稳定后推进：

- 语音面试复用 Agent Run 与事件模型。
- 长期记忆与上下文压缩。
- Interview Coach。
- 外部副作用审批工具。
- MCP Client 与动态工具发现。
- Observer/Judge 等多 Agent 模式。

## 6. API 与事件目标

最终 Agent API：

```text
POST /api/agent/runs
GET  /api/agent/runs/{runId}
GET  /api/agent/runs/{runId}/events
POST /api/agent/runs/{runId}/messages
POST /api/agent/runs/{runId}/cancel
POST /api/agent/runs/{runId}/pause
POST /api/agent/runs/{runId}/resume
POST /api/agent/approvals/{approvalId}
GET  /api/agent/runs/{runId}/trace
```

统一运行事件：

```text
run.started
step.started
tool.completed
approval.required
assistant.delta
run.waiting_user
run.completed
run.failed
```

持久化事件从已提交 Agent Step 派生，按 `stepSequence` 排序并以至少一次语义交付；前端负责按稳定事件身份去重和携带游标补读。`assistant.delta` 只提供实时打字效果，不逐 Token 持久化或重放，完整 Assistant 回应必须作为 Step 保存。

事件只包含前端所需的脱敏状态、工具名、来源、耗时与用户可见文本。

## 7. 测试策略

### 7.1 最高测试缝

- 首选 Agent Run API 与事件流，验证外部可见行为。
- 工具以输入 Schema、资源授权、Observation、超时、幂等和审批为契约测试缝。
- 只有高层测试无法证明持久化唯一性或并发语义时，才补 Repository/Service 测试。

### 7.2 测试约定

- JUnit 5 + Mockito + AssertJ。
- 中文 `@DisplayName`，复杂场景使用 `@Nested`。
- 核心持久化集成测试使用 H2。
- PostgreSQL JSONB 与 pgvector 使用独立基础设施验收环境；Redis Stream 只覆盖继续使用它的既有 Workflow。
- 真实 LLM 响应不作为确定性测试断言；循环使用 Fake ChatModel。

### 7.3 必测风险

- 幂等请求重复投递。
- 同一 Run 并发推进。
- 模型 429、工具超时和应用进程中断。
- Run 为 `RUNNING` 时的并发新消息、重复消息与过期问题回答。
- 重复工具调用与预算耗尽。
- 越权资源 ID 与 Prompt Injection。
- 审批前执行副作用。
- 外部副作用结果未知、用户核对与同一 `toolCallId` 重试。
- 固定资源撤权、重新授权和永久删除。
- 恢复后重复执行已完成 Step。
- `LEGACY` 行为回归。

## 8. 实施约束

- Controller 只负责路由、校验和委托。
- 业务编排与事务放在 Service；事务范围保持最小。
- 所有业务异常使用 `BusinessException(ErrorCode.XXX, "描述")`。
- Entity 不直接返回前端，响应使用 DTO/Response 与 `Result<T>`。
- Prompt 放在 `resources/prompts/`，使用 StringTemplate。
- Agent Runtime 的通用能力放在 `common/` 或 `infrastructure/`，业务工具留在业务模块。
- 每次只实现一个纵向交付；未通过当前验收前，不提前实现后续阶段。
- 保留并避开当前工作区中与 Agent 改造无关的未提交修改。

## 9. 实施顺序

严格按 G1 到 G6 的纵向切片推进，当前迭代、已完成事项、验证结果和下一开发动作只记录在 `progress.md`，不在本目标文档维护进度状态。

## 10. 非目标

- 不把所有 AI Workflow 强行改造成 Agent。
- 不在 Runtime 稳定前接入语音面试。
- 不在第一阶段实现任意外部副作用工具或通用 Shell/Web 工具。
- 不在第一阶段引入多 Agent、LangGraph4j 或 Spring AI Alibaba Graph。
- 不以“工具调用成功”作为 Agent 化完成标准。
- 不保存或展示隐藏思维链。
- 第一阶段不提供 Agent 数据删除、逐字段清除、加密擦除或独立敏感 Payload 生命周期；沿用现有数据保留方式，后续另行设计。
- 不用教学课程、练习问题或学习记录作为实施需求来源；实施以本文件和技术规范为准。
