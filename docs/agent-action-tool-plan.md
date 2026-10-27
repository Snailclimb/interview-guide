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
3. 简历编辑优化：已在“简历管理 → 编辑与优化”独立实现，基于 `resume_edit_drafts` 保存 AI 草稿，Agent 不重复接管；
4. `knowledgebase.query`：继续保持只读查询，不允许 Agent 自动上传、删除或重向量化；
5. `resume.draft.apply`：暂缓到草稿预览、确认、审计和回滚链路验收后再开放。

第一版动作 API 已固定为以下边界：

- `GET /api/agent/interview-sessions/{sessionId}/actions`：查询当前用户自己的动作草稿；
- `POST .../actions/training-plan-draft`：生成 `PROPOSED` 状态的训练计划草稿；
- `POST .../actions/practice-questions-draft`：生成 `PROPOSED` 状态的练习题草稿；
- `POST /api/agent/actions/{actionId}/confirm`：明确确认后保存草稿；
- `POST /api/agent/actions/{actionId}/cancel`：取消待确认动作。

草稿保存后，Agent 页面提供复制和 Markdown 下载；这是前端对已授权草稿结果的使用，不会新增业务写入权限。

当前“保存草稿”本身就是低风险执行结果，不会修改原始面试报告、简历或题库。后续动作必须复用同一动作资源和状态机，不能让模型通过自由文本拼接数据库写操作。

## 保护条件

- 所有资源必须通过当前登录用户做 owner 校验；
- 写动作使用独立的动作记录和状态机：`PROPOSED -> CONFIRMED -> RUNNING -> SUCCEEDED/FAILED/CANCELLED`；
- 每个动作必须有幂等键、超时、重试上限和失败补偿；
- 只保存参数摘要和结果摘要，不把完整简历、Prompt 或密钥写入审计；
- 默认关闭自动执行，前端必须展示预览和确认按钮；
- 工具异常不能让现有 Agent 对话或普通面试不可用；
- MCP 只作为外部系统适配层，不能绕过本地 owner、确认、审计和回滚策略。

## 实施顺序

1. 增加动作模型、状态机、幂等约束和审计事件；
2. 在 Agent 页面增加“待确认动作”卡片，展示预览、风险和确认/取消；
3. 增加训练草稿的复制、Markdown 导出、恢复和验收测试；
4. 仅在候选环境验收通过后，再考虑将练习题草稿应用到题库，以及接入 MCP 连接器。

本方案不改变现有 Agent 的建议模式：没有确认时，Agent 仍然只返回建议和预览。
