# 面试教练 Agent：Skill + RAG 接入方案（当前实现状态）

## 1. 目标

在不改变现有面试、RAG 聊天和知识库面试流程的前提下，让面试教练 Agent 真正复用已有能力：

- 普通面试按 `skillId` 加载现有 Skill 与 references。
- 知识库面试按 `knowledgeBaseId` 调用现有向量检索链路。
- Agent 最终回答统一由一次模型调用生成，并通过 SSE 流式返回。
- 前端展示真实的上下文、检索步骤和来源，不把前端状态伪装成独立模型节点。

当前实现已经从“接入方案”进入可验收阶段：Agent 对话、运行状态和来源摘要独立持久化，
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
8. 模型暂时繁忙时自动重试、切换备用 Provider，并在等待期间发送 SSE 心跳。
9. 页面刷新、SSE 断开和多实例取消后，可通过会话状态恢复或继续等待后台任务。
10. Agent 使用独立的 `SkillsTool + ToolCallingAdvisor` ChatClient，不启用全局会话记忆。

### 不包含

- 不做跨面试的长期记忆；当前只持久化当前面试对应的 Agent 对话和运行状态。
- 不开启全局 `MessageChatMemoryAdvisor`。
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
整理面试报告、薄弱点、历史对话和检索片段
        |
        v
安全清洗、字符预算、来源编号
        |
        +--> 独立 Agent 会话落库：用户消息 + assistant 占位消息 + 运行状态
        |
        v
Agent 专用 ChatClient（SkillsTool + ToolCallingAdvisor）生成最终教练回答
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
- 模型暂时繁忙：默认最多重试 6 次，指数退避单次最多 30 秒，等待期间每 10 秒发送心跳；
  可通过 `APP_AI_AGENT_MODEL_RETRY_ATTEMPTS`、`APP_AI_AGENT_RETRY_MAX_DELAY_SECONDS` 和
  `APP_AI_AGENT_RETRY_HEARTBEAT_INTERVAL_SECONDS` 调整。
- 首选 Provider 账号不可用或服务不可达：在模型尚未输出内容时切换备用 Provider。
- 最终仍失败：持久化失败原因，返回 Agent error SSE 事件，前端保留原始请求并支持重试。
- 单次回答最多持久化 32,000 字符，超过上限主动取消上游流，避免无限输出占用资源。

## 5. SSE 事件

```text
step(context, running)
step(context, completed)
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

步骤是可观察的编排状态。本期仍只使用一次最终生成模型调用；后续如果需要真正的分析节点，再拆出独立结构化调用。

## 6. 当前验收点与后续演进

当前已完成：

1. Skill、RAG、面试报告和短期对话上下文的固定预算编排。
2. 独立 Agent 会话/消息表、运行生命周期、进度和来源摘要恢复。
3. Provider 失败重试、备用 Provider、SSE 心跳、取消、超时回收和重复任务保护。
4. 前端 Markdown 展示、断线恢复、失败重试和当前运行来源展示。

后续按风险从低到高推进：

1. 先增加只读工具的明确审计事件和耗时指标，再考虑让模型自主选择工具。
2. 需要跨系统读取时，再把稳定的只读工具抽成 MCP；没有外部系统需求时不引入 MCP。
3. 写入型工具（训练任务、计划）必须先增加权限、幂等键、审计和回滚，再开放给 Agent。
4. 如需跨面试长期记忆，单独设计用户级记忆边界和删除策略，不复用当前会话表硬扩展。
