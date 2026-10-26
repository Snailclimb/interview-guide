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
14. 同一份面试报告支持多个独立 Agent 对话线程；线程标题、历史消息、运行状态和恢复任务彼此隔离。

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
        +--> 独立 Agent 对话线程落库：用户消息 + assistant 占位消息 + 运行状态
        |
        v
Agent Loop：短规划器 -> 有界循环状态 -> 只读 SkillsTool/ToolCallingAdvisor 与 RAG 观察 -> 最终回答
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
plan(content)                # 本次最多 3 个规划步骤
step(loop, running)
step(insight, running)
step(retrieval, running)
step(retrieval, completed)
sources([...])
step(action, running)
step(insight, waiting)      # 模型退避重试时周期性发送
delta(...)
step(action, completed)
step(loop, completed)
done
```

步骤是可观察的编排状态。Loop 有明确边界：规划器最多输出约 1,200 字符，最多规整为
`APP_AI_AGENT_LOOP_MAX_STEPS`（默认 3）个步骤；最终生成阶段复用已有的
`SkillsTool + ToolCallingAdvisor`，工具调用由 Spring AI 在当前请求内完成，不会再叠加一套无限自主循环。
模型失败时仍走现有 Provider 重试、上下文压缩和输出护栏；不开放写入型工具。

## 6. 长期记忆与应用重启恢复

### 轻量长期记忆

- 前端在 `localStorage` 生成稳定的浏览器级 `X-User-Id`，请求体同步携带 `memoryKey`。
- 后端表 `agent_user_memories` 只保存最近方向、最多 8 个薄弱点和最多 5 个训练目标。
- 当前面试报告始终优先于长期记忆；记忆仅作为参考上下文，不覆盖本场事实。
- `DELETE /api/agent/memory` 可清除当前浏览器记忆。未来接入真实账号后，只需替换记忆键来源。

### 应用重启恢复

- Agent 开始执行时已经将用户请求、助手占位消息、`RUNNING` 状态和 `runId` 写入数据库。
- 任务开始时会把空的“默认模型”解析为当前首选 Provider 并写入运行记录；应用重启后沿用该 Provider，避免默认模型切换导致恢复任务走错配置。
- 定时恢复器默认每 10 秒扫描运行超过 30 秒的 `RUNNING` 会话。
- 接管时使用会话级悲观锁并立即换发 `runId`，多实例只允许一个实例执行。
- 恢复是“从已保存请求重新生成”，不是模型 token 级续写；页面刷新后可通过现有会话接口继续等待并展示结果。
- 活跃任务不会被恢复器误接管；超过 15 分钟的旧任务仍由原有超时回收逻辑兜底。

## 7. 当前验收点与后续演进

当前已完成：

1. Skill、RAG、面试报告和短期对话上下文的固定预算编排。
   RAG Query Rewrite 会额外使用最近最多 6 条、总计最多 2400 字符的净化历史，支持多轮追问指代。
2. 独立 Agent 会话/消息表、运行生命周期、进度和来源摘要恢复。
   同一份面试报告下的多个 Agent 对话通过 `agent_session_id` 隔离，旧的只传面试报告 ID 的链接继续指向最早线程。
3. Provider 失败重试、备用 Provider、SSE 心跳、取消、超时回收和重复任务保护。
4. 前端 Markdown 展示、断线恢复、失败重试、Loop 步骤和当前运行来源展示。
5. Agent 只读 Skill 工具调用的成功/失败与耗时指标（不记录参数和返回内容）。
6. 有边界的 Agent Loop、浏览器级长期记忆和应用重启任务恢复。
7. 默认 Provider 固化与候选凭证过滤：恢复任务沿用启动时的首选 Provider；没有有效 API Key 的聊天 Provider 不进入备用候选链路。

关键 Agent 指标：

- `app.agent.runs{result}`：运行开始及终态数量。
- `app.agent.run.duration{result}`：单次 Agent 运行耗时。
- `app.agent.retries{reason}`：模型重试次数。
- `app.agent.provider.attempts{role,result}`：主/备用 Provider 尝试结果。
- `app.agent.tool.calls{tool,result}`：只读工具调用结果。
- `app.agent.tool.duration{tool,result}`：只读工具调用耗时。

建议验收顺序：

1. 新建一轮 Agent 请求，确认 SSE 依次出现 `context -> planning -> plan -> loop -> insight/retrieval -> action`，并能在前端看到执行计划。
2. 连续执行两场面试，确认同一浏览器只复用结构化薄弱点，不把原始问答注入长期记忆。
3. 在 Agent 运行期间重启应用，确认任务从 `RUNNING` 自动恢复且不会重复创建消息。
4. 调用清除记忆接口后重新执行，确认提示词中不再出现旧记忆。

正式登录和跨账号隔离验收可直接执行：

```powershell
pwsh -NoProfile -File .\scripts\formal-agent-acceptance.ps1 -BaseUrl http://localhost:18083 -CreateInterview -RunModelTests
pwsh -NoProfile -File .\scripts\auth-voice-schedule-acceptance.ps1 -BaseUrl http://localhost:18083
pwsh -NoProfile -File .\scripts\agent-restart-acceptance.ps1 -BaseUrl http://localhost:18083 -StatePath $env:TEMP\agent-recovery-state.json
```

前两个脚本验证正式登录下的 Agent 会话创建、同一报告多线程、重命名/归档、Loop 事件、运行状态持久化、真实模型流式完成，以及语音面试和面试日程的跨账号隔离。重启脚本先启动一个真实 Agent 请求；手动重启应用并等待超过 30 秒后，再使用 `-VerifyStatePath ... -Cleanup` 验证任务被接管、完成且消息没有重复。脚本不负责执行容器重启。

后续按风险从低到高推进：

1. 先增加只读工具的明确审计事件和耗时指标，再考虑让模型自主选择工具。
2. 需要跨系统读取时，再把稳定的只读工具抽成 MCP；没有外部系统需求时不引入 MCP。
3. 写入型工具（训练任务、计划）必须先增加权限、幂等键、审计和回滚，再开放给 Agent。
4. 如需跨面试长期记忆，单独设计用户级记忆边界和删除策略，不复用当前会话表硬扩展。

## 8. 暂缓待办

本轮先不实现以下能力，避免扩大验收面：

- Token、费用和 Provider 账单级统计；当前只保留运行、重试、Provider、工具调用次数和耗时指标。
- MCP Server/Client 接入；当前已有的 SkillsTool 和 ToolCallingAdvisor 足够支撑面试教练的只读能力。
- 长期记忆管理页面、记忆编辑和跨面试复杂召回；当前保留轻量结构化摘要和一键清除。
- Agent 写入训练计划、修改面试报告等高风险工具；需要权限、幂等、审计和回滚设计后再做。

## 9. 本轮已完成（Agent 生产化前四项）

- Agent 会话归属：所有 Agent API 在正式登录模式下使用服务端用户 ID，兼容模式下仍接受 `X-User-Id`；会话创建、读取、取消、重命名和归档均按 owner 校验；迁移前已有线程会从 `memory_key` 兼容迁移。普通面试、语音面试和面试日程也已接入同一账号隔离边界，语音 WebSocket 在握手阶段校验会话归属。
- 多线程管理：同一份面试报告可创建、切换、重命名和归档多个 Agent 对话，归档只隐藏会话，不删除历史消息。
- Agent Loop：规划结果会进入有限循环，逐步执行“动作 -> 观察”，将观察轨迹注入最终回答，并通过 SSE 暴露每一步；循环步骤受既有 `loopMaxSteps` 限制，不允许无限执行。
- 知识库/RAG owner 隔离：新登录用户上传的知识库及 RAG 会话写入 `owner_id`，列表、详情、下载、查询、题目管理、开始知识库面试、重向量化和删除均按当前用户校验；迁移前 `owner_id IS NULL` 的知识库继续作为部署级共享资料可读可维护，保证旧数据和现有题库不受影响。
- 浏览器验收：新增 Agent 会话隔离、页面刷新恢复、重命名和 Loop 事件的 Playwright 用例；本地和隔离候选环境均已通过 4/4，正式登录态由测试 fixture 覆盖。
- 真实候选验收：正式登录下连续 2 轮真实模型请求通过；应用重启后任务接管、回答完成、消息不重复验收通过。

> 当前已建立用户主体表、Redis 服务端会话和前端登录入口，Agent 的 `owner_key` 已切换为正式登录主体 ID；简历、面试会话、知识库和 RAG 会话已增加 `owner_id` 隔离。知识库通过 NULL owner 兼容迁移前的部署级共享资料；个人知识库的细粒度共享、协作成员和管理员治理仍不在本次范围内。
