# 面试教练 Agent：Skill + RAG 接入方案（当前实现状态）

## 1. 目标

在不改变现有面试、RAG 聊天和知识库面试流程的前提下，让面试教练 Agent 真正复用已有能力：

- 普通面试按 `skillId` 加载现有 Skill 与 references。
- 知识库面试按 `knowledgeBaseId` 调用现有向量检索链路。
- Agent 通过一个有边界的 Loop 完成规划、只读观察和最终回答，并通过 SSE 流式返回。
- 前端展示真实的上下文、检索步骤和来源，不把前端状态伪装成独立模型节点。

当前实现已经从“接入方案”进入可验收阶段：Agent 对话、运行状态、来源摘要和有限长期记忆独立持久化，
不会改写原有面试记录、RAG 会话或向量数据。

## 2. 本期范围

### 包含

1. 扩展面试详情 DTO，暴露 `skillId`，不改变既有字段含义。
2. 新增 Agent 上下文编排服务，复用：
   - `InterviewHistoryService`
   - `InterviewSkillService`
   - `KnowledgeBaseQueryService.retrieveOnly(...)`
   - `LlmProviderRegistry`
   - `PromptSanitizer`
3. 普通面试加载 Skill references；知识库面试检索 pgvector 片段。
4. SSE 增加检索状态和来源事件。
5. 前端展示真实工作流和引用来源。
6. 检索失败时降级为无 RAG 上下文，Agent 仍可基于面试报告回答。
7. Agent 会话消息、运行生命周期和可恢复的工作流进度独立持久化。
   失败、取消或断线恢复时会额外返回已生成的半截回答用于展示，但不会把未完成回答加入下一轮模型历史。
8. 模型暂时繁忙时自动重试、切换备用 Provider，并在等待期间发送 SSE 心跳。
9. 页面刷新、SSE 断开和多实例取消后，可通过会话状态恢复或继续等待后台任务。
10. Agent 使用独立的 `SkillsTool + ToolCallingAdvisor` ChatClient，不启用全局会话记忆。
11. Agent Loop 使用短规划器；规划失败时回退到默认计划，不影响原有回答能力。
12. 使用浏览器级记忆键保存有限的 Skill、薄弱点和训练目标摘要；支持主动清除。
13. 应用重启后扫描遗留的 RUNNING 任务，使用数据库锁接管并从已保存的用户请求重新执行。

### 不包含

- 不保存原始问答形式的长期记忆；长期记忆只保留有限结构化摘要。
- 不开启全局 `MessageChatMemoryAdvisor`，避免影响现有应用会话。
- 不引入 MCP Server/Client。
- 不提供会修改面试报告、知识库或训练数据的写入型工具。
- 不改变现有 RAG 问答接口、RAG 会话表和向量化流程。
- 不开放会修改业务数据的工具；当前仅保留受控的 `SkillsTool` 只读加载能力，避免影响现有面试链路。

## 3. 主流程

```text
用户进入面试教练
        |
        v
读取面试详情（只读）
        |
        +--> 普通面试：skillId -> Skill references
        |
        +--> 知识库面试：knowledgeBaseId -> Query rewrite -> pgvector 检索
        |
        v
整理面试报告、薄弱点、历史对话、长期记忆和检索片段
        |
        v
安全清洗、字符预算、来源编号
        |
        +--> 独立 Agent 会话落库：用户消息 + assistant 占位消息 + 运行状态
        |
        v
Agent Loop：短规划器 -> 只读 SkillsTool/ToolCallingAdvisor 与 RAG 观察 -> 最终回答
        |
        +--> SSE：步骤事件 + 来源事件 + 增量文本 + done
        |
        +--> 完成/失败/取消落库；断线后由前端轮询会话状态恢复
```

## 4. 上下文策略

### 普通面试

- 使用面试详情中的 `skillId`。
- 通过 `InterviewSkillService.buildEvaluationReferenceSectionSafe` 加载该方向的 references。
- references 仅作为只读资料注入，不允许模型把资料中的指令当成系统指令。

### 知识库面试

- 使用面试详情中的 `knowledgeBaseId`。
- 将用户当前问题、面试改进项和低分回答拼成检索问题。
- 调用现有 `retrieveOnly`，复用 Query Rewrite、TopK、相似度阈值和 pgvector。
- 只把实际命中的片段注入 Agent，不调用现有 RAG 的最终回答生成，避免二次回答和重复流式输出。

### 失败降级

- Skill 加载失败：记录警告，继续使用面试报告。
- RAG 检索失败或无命中：记录 warning，继续使用面试报告和对话历史。
- 模型暂时繁忙：默认最多重试 6 次，指数退避单次最多 30 秒；模型等待首 token 或退避等待期间每 10 秒发送心跳，
  避免长请求被代理误判为断线；可通过 `APP_AI_AGENT_MODEL_RETRY_ATTEMPTS`、`APP_AI_AGENT_RETRY_MAX_DELAY_SECONDS` 和
  `APP_AI_AGENT_RETRY_HEARTBEAT_INTERVAL_SECONDS` 调整。
- 首选 Provider 账号不可用或服务不可达：在模型尚未输出内容时切换备用 Provider。
- 最终仍失败：持久化失败原因，返回 Agent error SSE 事件，前端保留原始请求并支持重试。
- 错误会区分上下文超限、账号/API Key 权限、服务繁忙和响应超时，分别给出可执行处理建议；上下文超限不会盲目重复调用模型。
- 模型返回输出 token 范围限制时单独提示模型能力边界，避免与前端消息字符限制混淆。
- Agent 不再向 Provider 注入固定的 `max-output-tokens=3000`；仅保留应用侧 32,000 字符安全护栏，防止无限流式输出占用资源。
- 明确发生上下文超限时，单独使用紧凑 Prompt：压缩较早历史，并压缩面试报告、Skill references 和 RAG 片段后只重试一次。
- 常见网关和上游瞬态故障（500/502/503/504、连接重置和连接超时）也纳入自动重试与备用 Provider 切换范围。
- 单次回答最多持久化 32,000 字符，超过上限主动取消上游流，避免无限输出占用资源。

## 5. SSE 事件

```text
step(context, running)
step(context, completed)
step(planning, running)
step(planning, completed)
step(insight, running)
step(retrieval, running)
step(retrieval, completed)
sources([...])
step(action, running)
step(insight, waiting)      # 模型退避重试时周期性发送
delta(...)
step(action, completed)
done
```

步骤是可观察的编排状态。Loop 有明确边界：规划器最多输出约 1,200 字符，最终生成仍使用已有的 Provider 重试、上下文压缩和输出护栏；不做无限自主循环，也不开放写入型工具。

## 6. 长期记忆与应用重启恢复

### 轻量长期记忆

- 前端在 `localStorage` 生成稳定的浏览器级 `X-User-Id`，请求体同步携带 `memoryKey`。
- 后端表 `agent_user_memories` 只保存最近方向、最多 8 个薄弱点和最多 5 个训练目标。
- 当前面试报告始终优先于长期记忆；记忆仅作为参考上下文，不覆盖本场事实。
- `DELETE /api/agent/memory` 可清除当前浏览器记忆。未来接入真实账号后，只需替换记忆键来源。

### 应用重启恢复

- Agent 开始执行时已经将用户请求、助手占位消息、`RUNNING` 状态和 `runId` 写入数据库。
- 定时恢复器默认每 10 秒扫描运行超过 30 秒的 `RUNNING` 会话。
- 接管时使用会话级悲观锁并立即换发 `runId`，多实例只允许一个实例执行。
- 恢复是“从已保存请求重新生成”，不是模型 token 级续写；页面刷新后可通过现有会话接口继续等待并展示结果。
- 活跃任务不会被恢复器误接管；超过 15 分钟的旧任务仍由原有超时回收逻辑兜底。

## 7. 当前验收点与后续演进

当前已完成：

1. Skill、RAG、面试报告和短期对话上下文的固定预算编排。
   RAG Query Rewrite 会额外使用最近最多 6 条、总计最多 2400 字符的净化历史，支持多轮追问指代。
2. 独立 Agent 会话/消息表、运行生命周期、进度和来源摘要恢复。
3. Provider 失败重试、备用 Provider、SSE 心跳、取消、超时回收和重复任务保护。
4. 前端 Markdown 展示、断线恢复、失败重试、Loop 步骤和当前运行来源展示。
5. Agent 只读 Skill 工具调用的成功/失败与耗时指标（不记录参数和返回内容）。
6. 有边界的 Agent Loop、浏览器级长期记忆和应用重启任务恢复。

关键 Agent 指标：

- `app.agent.runs{result}`：运行开始及终态数量。
- `app.agent.run.duration{result}`：单次 Agent 运行耗时。
- `app.agent.retries{reason}`：模型重试次数。
- `app.agent.provider.attempts{role,result}`：主/备用 Provider 尝试结果。
- `app.agent.tool.calls{tool,result}`：只读工具调用结果。
- `app.agent.tool.duration{tool,result}`：只读工具调用耗时。

建议验收顺序：

1. 新建一轮 Agent 请求，确认 SSE 依次出现 `context -> planning -> insight/retrieval -> action`。
2. 连续执行两场面试，确认同一浏览器只复用结构化薄弱点，不把原始问答注入长期记忆。
3. 在 Agent 运行期间重启应用，确认任务从 `RUNNING` 自动恢复且不会重复创建消息。
4. 调用清除记忆接口后重新执行，确认提示词中不再出现旧记忆。

后续按风险从低到高推进：

1. 先增加只读工具的明确审计事件和耗时指标，再考虑让模型自主选择工具。
2. 需要跨系统读取时，再把稳定的只读工具抽成 MCP；没有外部系统需求时不引入 MCP。
3. 写入型工具（训练任务、计划）必须先增加权限、幂等键、审计和回滚，再开放给 Agent。
4. 如需跨面试长期记忆，单独设计用户级记忆边界和删除策略，不复用当前会话表硬扩展。
