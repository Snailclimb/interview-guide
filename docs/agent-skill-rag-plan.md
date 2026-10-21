# 面试教练 Agent：Skill + RAG 接入方案

## 1. 目标

在不改变现有面试、RAG 聊天和知识库面试流程的前提下，让面试教练 Agent 真正复用已有能力：

- 普通面试按 `skillId` 加载现有 Skill 与 references。
- 知识库面试按 `knowledgeBaseId` 调用现有向量检索链路。
- Agent 最终回答统一由一次模型调用生成，并通过 SSE 流式返回。
- 前端展示真实的上下文、检索步骤和来源，不把前端状态伪装成独立模型节点。

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

### 不包含

- 不新增 Agent 数据表，不做持久化长期记忆。
- 不开启全局 `MessageChatMemoryAdvisor`。
- 不引入 MCP Server/Client。
- 不改变现有 RAG 问答接口、RAG 会话表和向量化流程。
- 不让模型在本期自主决定工具调用，避免影响现有面试链路。

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
        v
Plain ChatClient 生成最终教练回答
        |
        v
SSE：步骤事件 + 来源事件 + 增量文本 + done
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
- 模型调用失败：返回 Agent error SSE 事件，前端支持重试。

## 5. SSE 事件

```text
step(context, running)
step(context, completed)
step(insight, running)
step(retrieval, running)
step(retrieval, completed)
sources([...])
step(action, running)
delta(...)
step(action, completed)
done
```

步骤是可观察的编排状态。本期仍只使用一次最终生成模型调用；后续如果需要真正的分析节点，再拆出独立结构化调用。

## 6. 后续演进

验收本期后，再按风险从低到高推进：

1. Agent 会话和摘要持久化，复用语音面试的窗口、摘要和字符预算策略。
2. 内部只读工具：`read_interview_report`、`retrieve_knowledge`、`load_skill_reference`。
3. 写入型工具：生成训练任务、保存训练计划，增加权限和幂等控制。
4. 只有在需要连接 GitHub、Jira 或外部知识系统时，再把稳定工具抽成 MCP。
