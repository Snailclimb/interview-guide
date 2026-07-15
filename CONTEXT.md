# AI 面试领域

本上下文描述一次 AI 面试、其 Agent 执行以及历史评估之间的业务边界，用于统一产品、文档与代码中的领域语言。

## Language

**Interview Session（面试会话）**：
用户从开始到结束的一次面试尝试，也是面试业务生命周期的权威。用户再次练习时创建新的 Interview Session，不复用已终止的会话。
_Avoid_: 练习容器、长期训练任务

**Agent**：
定义面试官目标、能力与可用工具的角色，不代表某一次具体执行。
_Avoid_: Agent Run、面试会话

**Agent Run（Agent 运行）**：
Agent 在一个 Interview Session 中的一次可持久化执行实例，也是 Agent 执行生命周期的权威。它可以驱动面试状态更新，但不能覆盖已取消或已完成的 Session 终态。
_Avoid_: Agent、Interview Session

**In-Process Run Execution（进程内 Run 执行）**：
第一阶段由接收用户消息的 Spring Boot 实例在当前进程内执行一个有界 Agent Turn。PostgreSQL 的条件状态转换和乐观锁保证同一 Run 只有一个 Turn 能推进；应用中断后遗留的 `RUNNING` Run 转为 `PAUSED`，由用户恢复原 Run。
_Avoid_: Agent 专用消息队列、分布式 Worker、自动跨实例接管

**Agent Turn（Agent 轮次）**：
从 Run 接受一条针对当前问题的用户回答开始，到 Run 再次等待用户输入或进入终态为止的一次执行。一个 Run 同时最多有一个进行中的 Agent Turn。
_Avoid_: 单次模型调用、整场面试、React 渲染

**Agent Step（Agent 步骤）**：
Run 中一条不可变的执行事实，使用 Run 内单调递增的序号记录模型、工具、状态变化或错误。Step 是恢复与审计的事实源，不保存隐藏思维链。
_Avoid_: Checkpoint、Agent Turn、应用日志

**Durable Run Event（持久化运行事件）**：
从已提交 Agent Step 派生的用户可见事件，以 Run 内 `stepSequence` 排序。事件流按至少一次语义交付，客户端使用 Run、Step 序号与事件类型组成的身份去重，并可从最后收到的 Step 序号之后补读。
_Avoid_: Token Delta、Redis 消费位置、恰好一次网络交付

**Assistant Delta（助手增量）**：
模型生成过程中用于实时打字效果的临时文本片段，不逐 Token 持久化，也不保证断线重放。完整 Assistant 回应必须作为 Agent Step 持久化并可在重连后读取。
_Avoid_: 完整 Assistant 回应、持久化运行事件、恢复事实源

**Checkpoint（检查点）**：
根据已提交 Agent Step 生成的可恢复状态快照，记录最后应用的 Step 序号。Checkpoint 可以从完整且连续的
Step 重建，不是独立于执行账本的事实源；快照损坏时必须丢弃并重建，Step 缺失、断裂或无法解释时 Run
进入暂停而不猜测状态。
_Avoid_: Agent Step、数据库备份、业务实体副本

**Evidence Store（证据库）**：
保存用户原始回答、成功的工具 Observation 和确定性业务记录的权威证据集合。模型判断与决策摘要只能说明执行过程，不能单独成为候选人能力证据。
_Avoid_: 模型上下文、Checkpoint 摘要、完整消息注入

**Evidence Packet（证据包）**：
Context Builder 针对当前目标从 Evidence Store 选择的有限证据集合，包含当前回答、少量近期原文、相关历史片段及其来源标识。它受独立 Token 配额约束，不代表 Evidence Store 的完整副本。
_Avoid_: 全量对话历史、无来源摘要、长期事实库

**Context Summary（上下文摘要）**：
从原始证据派生、用于压缩模型上下文的可重建缓存，记录覆盖到的 Step 序号和来源 Step。摘要不得递归总结其他摘要；与原始证据冲突时，以原始证据为准并重新生成。
_Avoid_: 业务事实、候选人能力证据、不可追溯的模型记忆

**Prompt Data Boundary（Prompt 数据边界）**：
由服务端生成不可预测标记、用于包裹用户回答、简历、知识库片段、历史记录和工具输出等不可信 Prompt 数据的语义分隔。它帮助模型区分数据与指令，但不是授权、工具治理或状态约束的安全边界。
_Avoid_: 权限检查、内容绝对安全保证、Runtime Policy

**Trusted Prompt Instruction（可信 Prompt 指令）**：
可以进入 Agent 指令区的 Runtime Policy 和 Session 固定的平台发布 Skill。其他来源即使包含命令式文字，也只能作为 Prompt Data Boundary 中的数据。
_Avoid_: 用户回答、简历文本、知识库片段、工具输出、用户自定义 Skill

**Session Resource Manifest（会话资源清单）**：
一个 Interview Session 在首次启动 Agent Run 时确定的不可变资源版本集合，包括简历版本、Skill 版本和知识库快照。Skill 始终进入清单，简历和知识库可以不进入；资源一旦进入清单，就成为该 Session 的必需资源。该 Session 下的所有 Agent Run 只能继承这份清单，不能重新选择版本；新资源版本只影响新的 Interview Session。清单固定内容版本但不固化访问权限，每次读取仍需实时授权。
_Avoid_: Run 局部资源选择、Checkpoint 正文副本、最新资源版本、永久访问授权

**Live Resource Authorization（实时资源授权）**：
在每次资源读取和每个新模型 Step 开始前，对 Session Resource Manifest 中的资源重新检查访问权限。授权结果只覆盖已经开始的当前 Step，不追溯撤销已经提交给模型的内容；Step 执行期间发生撤权时，当前 Step 完成后暂停 Run，禁止开始下一 Step。
_Avoid_: Run 创建时的一次性授权、永久访问授权、追溯删除模型上下文

**Answer Message（回答消息）**：
用户针对当前 Agent 问题提交的不可变回答，具有稳定的消息标识和被回答问题的关联。相同消息可幂等重试，Agent 执行期间不接受补充或另一份回答。
_Avoid_: Agent Turn、排队中的补充消息

**Current Agent Question（当前 Agent 问题）**：
某个 Agent Run 当前允许用户回答的问题的不透明身份；它只在 Run 等待用户时有效，不等同于 LEGACY 面试题单的索引或题目正文。
_Avoid_: currentQuestionIndex、LEGACY Question、题目文本

**Tool（工具）**：
Agent 被授权调用的一项命名能力；`toolId` 标识工具定义，而不标识某次具体执行。
_Avoid_: Tool Call、任意代码执行入口

**Tool Call（工具调用）**：
Agent 对某个 Tool 发起的一次具体调用，具有稳定且唯一的 `toolCallId`。内部写入以 `toolCallId` 作为幂等依据，同一调用重试时返回原执行结果而不重复应用副作用。
_Avoid_: Tool、模型步骤

**Authorization Denial（授权拒绝）**：
Tool Call 请求了当前 Run 未授权的工具或资源时产生的安全结果；工具不会执行，模型和用户只获得不泄露资源存在性及权限细节的通用拒绝信息。
_Avoid_: 工具失败、资源不存在、用户审批拒绝

**Reconciliation（外部事实核对）**：
在外部副作用的执行结果未知时，由用户确认外部事实的过程。确认操作未发生后才能使用原 `toolCallId` 重试，未知状态本身不得被当作失败或安全重试依据。
_Avoid_: 重新审批、自动重试、等待连接

**Waiting Reconciliation（等待外部事实核对）**：
Agent Run 等待用户核对外部副作用结果的非终态；此时 ReAct 循环停止，不能继续调用工具。
_Avoid_: `WAITING_CONNECTED`、`WAITING_APPROVAL`、`FAILED`

**Run Budget（运行预算）**：
Agent Run 在步骤、工具调用、运行时间、Token 与费用维度上的不可突破总上限。所有尝试，包括失败和重试，都会消耗相应预算。
_Avoid_: 建议额度、可透支配额

**Closure Reserve（收尾保留）**：
Run 创建时从总预算中预留、仅用于生成最终用户回应的额度，不是总预算之外的额外配额。收尾调用失败后不再调用模型，改用确定性模板结束。
_Avoid_: 超额预算、无限兜底重试

**Evaluation Report（评估报告）**：
一次已结束 Interview Session 产生的评估结果；新的面试会话只能将完整的历史评估作为只读参考，不继承旧会话的进度、能力覆盖或剩余预算。已取消会话不提供评估，因保护策略提前结束的不完整评估不能用于后续 Agent 决策。
_Avoid_: Checkpoint、当前面试进度

**Capability Evidence（能力证据）**：
对候选人某项能力的结构化判断，必须引用一个或多个 `sourceStepId` 指向原始回答、成功的工具 Observation 或确定性业务记录。模型自身的判断、Context Summary 或无来源结论不能单独构成能力证据。
_Avoid_: Context Summary、模型印象、无来源评分

**Evaluation Completeness（评估完整性）**：
评估是否包含足够面试证据、可用于未来面试参考的资格。`COMPLETE` 评估可作为历史参考，`INCOMPLETE` 评估只向当前用户展示。
_Avoid_: 面试得分、Run 状态、报告生成状态

**Paused Interview（已暂停面试）**：
暂时停止但尚未终止的 Interview Session；用户暂停、自动重试耗尽但 Checkpoint 可用，或固定资源的读取权限被撤销时进入此状态。若撤权发生在已授权 Step 执行期间，则该 Step 完成后暂停且不得开始下一 Step。恢复条件满足后继续同一次面试及原 Agent Run；资源场景只能重新授权清单中固定的同一版本，不能切换版本。
_Avoid_: 已取消面试、重新面试

**Cancelled Interview（已取消面试）**：
由用户终止且不可继续的 Interview Session；再次练习必须创建新会话，且不继承已取消会话的进度或评估。
_Avoid_: 已暂停面试、可恢复面试

**Failed Interview（失败面试）**：
因 Checkpoint 不可用、状态不可重建、固定的必需资源版本被永久删除或其他不可恢复约束而终止的 Interview Session。失败是终态，重新面试必须创建新会话。
_Avoid_: 暂时技术故障、重试耗尽、已暂停面试
