# Agent 执行型能力接入方案

## 现状边界

当前 Agent 已经能够读取面试报告、Skill、RAG 和长期记忆，并通过有限 Loop 生成建议。
`SkillsTool` 和 `ToolCallingAdvisor` 已接入，但只允许读取 Skill 资料；没有开放修改简历、面试
报告、知识库或调用外部系统的业务写工具。

Agent 自身的会话、记忆、审计、用量和 Loop 检查点属于内部运行数据，不应被理解为 Agent
可以任意修改用户业务数据。

## 推荐的执行链路

```text
用户请求
  -> Agent 识别意图
  -> 只读上下文与工具观察
  -> 生成执行计划和变更预览
  -> 用户明确确认
  -> 幂等执行一个已授权动作
  -> 保存结果、审计和可回滚信息
  -> Agent 汇报结果
```

默认只执行到“变更预览”。没有用户确认时，模型不能直接调用写工具。

## 工具契约

每个工具都需要声明以下固定信息：

- `toolId`：稳定的工具标识，例如 `resume.draft.optimize`；
- `riskLevel`：`READ_ONLY`、`DRAFT_WRITE`、`BUSINESS_WRITE`；
- `requiresConfirmation`：是否必须用户确认；
- `resourceType/resourceId`：资源归属和访问边界；
- `idempotencyKey`：防止重试重复写入；
- `preview`：执行前展示的变更摘要；
- `rollback`：是否支持撤销，以及撤销凭证；
- `auditAction`：低敏审计事件名称。

## 第一批工具

先接低风险、可回滚动作，不影响现有面试流程：

1. `interview.plan.draft`：已经接入第一版，生成训练计划草稿，只保存到 Agent 动作草稿区；
2. `practice.questions.draft`：已经接入第二版，基于薄弱点生成练习题草稿，确认后保存到独立草稿区；
   已确认的练习题或训练计划草稿可以从 Agent 页面发起下一轮专项文字面试，出题时只读注入训练重点；
3. `agent.memory.save`：生成一条不超过 160 字的结构化长期记忆，确认后写入现有记忆服务，支持后续编辑和删除；
4. `interview.title.rename`：生成面试记录名称建议，确认后只修改该面试的显示名称；
5. `training.task.create`：已经接入，确认后创建独立训练任务；任务支持待开始、进行中、已完成和已取消，不修改原始面试资料；
6. `interview.report.export`：已经接入，确认后校验面试归属并下载 PDF，不修改面试内容；
7. 简历编辑优化：已在“简历管理 → 编辑与优化”独立实现，基于 `resume_edit_drafts` 保存 AI 草稿，Agent 不重复接管；
8. `knowledgebase.query`：继续保持只读查询，不允许 Agent 自动上传、删除或重向量化；
9. `resume.draft.apply`：暂缓到草稿预览、确认、审计和回滚链路验收后再开放。

第一版动作 API 已固定为以下边界：

- `GET /api/agent/interview-sessions/{sessionId}/actions`：查询当前用户自己的动作草稿；
- `POST .../actions/training-plan-draft`：生成 `PROPOSED` 状态的训练计划草稿；
- `POST .../actions/practice-questions-draft`：生成 `PROPOSED` 状态的练习题草稿；
- `POST .../actions/training-task-draft`：生成 `PROPOSED` 状态的训练任务草稿；
- `POST .../actions/interview-report-export-draft`：生成 `PROPOSED` 状态的报告导出动作；
- `POST .../actions/memory-draft`：生成 `PROPOSED` 状态的长期记忆草稿，服务端保存分类和适用方向元数据；
- `POST .../actions/interview-title-draft`：生成 `PROPOSED` 状态的面试名称草稿；
- `PATCH /api/agent/actions/{actionId}`：仅允许用户在 `PROPOSED` 状态下人工编辑草稿；
- `POST /api/agent/actions/{actionId}/confirm`：明确确认后保存草稿；
- `POST /api/agent/actions/{actionId}/retry`：仅允许重试 `FAILED` 动作，最多实际执行 3 次；
- `POST /api/agent/actions/{actionId}/cancel`：取消待确认动作。
- `GET /api/agent/training-tasks`：读取当前用户的训练任务，可按状态筛选；
- `PATCH /api/agent/training-tasks/{taskId}`：推进当前用户自己的训练任务状态。

草稿保存后，Agent 页面提供人工编辑、复制和 Markdown 下载；这是对已授权草稿结果的使用，不会新增原始业务数据写入权限。

当用户选择“针对性训练”或“制定训练计划”并完成一轮 Agent 对话后，前端会根据已持久化的最终回答自动提出对应的草稿动作。该动作仍然只是 `PROPOSED`，重复生成使用稳定幂等键，不会自动确认，也不会因为动作草稿失败而把已经完成的 Agent 回答判为失败。用户仍可手动编辑、确认或取消。

训练计划和练习题确认后仍然只保存到独立动作区；已确认的练习题只作为下一轮出题的只读上下文，不回写原面试。训练任务确认后进入独立任务表，用户手动推进任务状态。长期记忆复用现有结构化记忆服务；面试名称只更新当前面试的 `title` 字段。四类动作都不修改原始回答、评分、报告、题目或上传简历。后续动作必须复用同一动作资源和状态机，不能让模型通过自由文本拼接数据库写操作。

## 保护条件

- 所有资源必须通过当前登录用户做 owner 校验；
- 写动作使用独立的动作记录和状态机：`PROPOSED -> CONFIRMED -> RUNNING -> SUCCEEDED/FAILED/CANCELLED`；
- 每个动作必须有幂等键、超时、重试上限和失败补偿；
- 应用重启或进程异常后，超过 5 分钟仍处于 `RUNNING` 的动作会被回收为 `FAILED`，不会自动重放可能产生副作用的业务写入；
- 只保存参数摘要和结果摘要，不把完整简历、Prompt 或密钥写入审计；
- 默认关闭自动执行，前端必须展示预览和确认按钮；
- 工具异常不能让现有 Agent 对话或普通面试不可用；
- 失败动作必须显示可重试状态；重试次数上限为 3 次，达到上限后要求重新生成草稿；
- MCP 只作为外部系统适配层，不能绕过本地 owner、确认、审计和回滚策略。

## 实施顺序

1. 增加动作模型、状态机、幂等约束和审计事件；
2. 在 Agent 页面增加“待确认动作”卡片，展示预览、风险和确认/取消；
3. 增加训练草稿的复制、Markdown 导出、恢复和验收测试；
4. 增加长期记忆和面试名称的确认执行，并保留编辑、取消和审计；
5. 仅在候选环境验收通过后，再考虑将练习题草稿应用到题库，以及接入 MCP 连接器。

训练任务是第一类“可持续推进”的业务动作：Agent 负责提出任务和说明，用户确认创建，用户自己按“待开始 → 进行中 → 已完成”推进；服务端拒绝跳过开始直接完成，也不允许已结束任务回退。Agent 不代替用户伪造完成结果，也不自动修改面试评分。

本方案不改变现有 Agent 的建议模式：没有确认时，Agent 仍然只返回建议和预览。
