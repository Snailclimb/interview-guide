# AI Agent 开发面试参考

## 一、Agent 基础

### Agent 定义与核心架构
- Agent = LLM + Planning + Memory + Tools
- Agent Loop：感知(Perceive) -> 推理(Reason) -> 行动(Act) -> 观察(Observe) 循环
- 终止条件：任务完成、达到最大步数、用户中断、错误熔断

### Agent vs 传统编程 vs 工作流
- 决策者差异：Agent 由 AI 决策下一步，工作流由预定义路径驱动
- 适用场景判断：确定性任务用工作流，开放性任务用 Agent

### Agent 范式
- ReAct：Thought-Action-Observation 循环，边推理边执行
- Plan-and-Execute：先规划全局步骤，再逐步执行
- Reflection：自我反思与修正（Reflexion / Self-Refine / CRITIC）

### 多 Agent 系统
- Orchestrator-Subagent：主从模式，编排器分配任务
- Peer-to-Peer：对等模式，Agent 间平等协作
- A2A（Agent-to-Agent）通信协议

### 安全
- Prompt Injection 攻击类型与防御（执行层沙箱、认知层隔离、决策层人机协同）
- Agent 权限边界设计（最小权限原则）
- 敏感操作审批机制

---

## 二、LLM 调用

### Token 与上下文窗口
- Token 是计费和性能的基本单位（非字符）
- 上下文窗口 = System Prompt + User Prompt + 历史 + RAG + 工具定义 + 输出
- Token 预算：window >= input_tokens + max_output_tokens
- Prompt Caching：静态内容前置、动态内容后置

### 采样参数
- Temperature：控制随机性（低=确定性，高=创造性）
- Top-p（核采样）和 Top-k：缩小候选词池
- Presence/Frequency Penalty：抑制重复
- 推理模型（o1/o3）的参数限制

### Function Calling
- JSON Schema 定义工具接口
- 工具粒度设计：原子操作 vs 组合操作
- 并行工具调用（Parallel Tool Calling）
- 错误处理：工具调用失败时的重试与降级策略

### 结构化输出与流式输出
- JSON Mode vs Structured Output（Schema 约束）
- SSE（Server-Sent Events）机制与 TTFT 优化
- 流式场景下的工具调用处理

### 成本优化
- 输入/输出 Token 定价差异（2-5 倍）
- 路由策略：简单问题用小模型、复杂问题用大模型
- 缓存策略：语义缓存、精确匹配缓存

---

## 三、MCP 协议

### MCP 定位
- MCP（Model Context Protocol）= AI 的 USB-C，统一工具接入标准
- MCP vs Function Calling vs Agent：MCP 是协议标准，Function Calling 是 LLM 能力，Agent 是系统概念
- 四层关系：Function Calling（基础） -> Prompt（意图） -> MCP（连接） -> Skills（编排）

### MCP 四大核心能力
- Resources：只读数据源 | Tools：可执行操作 | Prompts：模板化提示词 | Sampling：LLM 推理委托

### 架构与传输
- 四层：Host（宿主） -> Client（协议客户端） -> Server（工具服务） -> Data Source
- JSON-RPC 2.0 通信协议（轻量、传输无关、易调试）
- stdio：本地 IPC；Streamable HTTP：远程/生产（已废弃 HTTP+SSE）

### 生产实践
- 工具幂等性设计、退避策略与 P99 延迟目标
- 上下文窗口管理（大结果截断、分页）
- 安全考量（输入校验、权限控制、审计日志）

---

## 四、RAG 检索增强

### 基本原理
- RAG = 信息检索 + LLM 生成
- 离线索引（加载、清洗、分块、Embedding、存储）+ 在线检索（查询向量化、相似度搜索、上下文构建、生成）
- 核心优势：知识时效性、减少幻觉、数据安全、领域适配

### 分块与 Embedding
- 固定长度 vs 语义分块 vs 递归分块
- 分块大小与语义完整性的权衡
- 通用 vs 领域微调 Embedding 模型选择

### 向量检索
- ANN：用 5% 召回率损失换 100 倍速度
- HNSW：<1000 万向量、高召回、高内存 | IVFFLAT：1000 万-1 亿、内存友好
- 距离度量：余弦相似度、内积、欧几里得距离
- 混合检索：向量 + BM25 + RRF 融合（生产最佳实践）

### 局限与治理
- GIGO：检索质量决定生成质量
- 上下文窗口噪声、TTFT 增加、检索召回率评估

---

## 五、上下文工程

### 概念
- Agent = Model + Harness（模型之外的一切都是 Harness）
- 模型决定上限，Harness 决定下限

### 六层 Harness 架构
1. L1 信息边界：System Prompt、约束条件、角色定义
2. L2 工具系统：工具注册、Schema 定义、权限控制
3. L3 执行编排：Agent Loop、条件分支、并行执行
4. L4 记忆与状态：短期记忆、长期记忆、状态持久化
5. L5 评估与可观测：质量评估、链路追踪、指标监控
6. L6 约束与恢复：错误处理、重试策略、降级方案

### Token 预算与设计模式
- 40% 上下文利用率阈值（超过后质量急剧下降）
- 上下文压缩：摘要、裁剪、遗忘
- 渐进式披露：L1 元数据常驻 + L2 正文按需 + L3 资源隔离
- Lost in the Middle 问题与应对

### 实践案例
- OpenAI：机械约束优于文档约束、熵管理
- Anthropic：GAN 式三 Agent 架构、上下文重置策略
- Stripe：Minions 系统

---

## 面试高频追问

### Agent 基础
- Agent Loop 中如何防止无限循环？
- ReAct 和 Plan-and-Execute 各自的优缺点和适用场景？
- 多 Agent 系统中如何保证一致性？

### LLM 调用
- Token 超出上下文窗口时怎么处理？
- Function Calling 和直接在 Prompt 里写工具有什么区别？
- 如何降低 LLM 调用的 P99 延迟？

### MCP 协议
- MCP 和直接用 Function Calling 有什么区别？
- stdio 和 Streamable HTTP 分别适合什么场景？
- 如何设计一个生产级的 MCP Server？

### RAG
- 分块策略如何选择？过大或过小分别有什么问题？
- 向量检索和关键词检索各自的优缺点？为什么需要混合检索？
- RAG 系统线上出现幻觉，排查思路是什么？

### 上下文工程
- Agent 上下文超出窗口时如何处理？
- AGENTS.md / SKILL.md 的设计原则是什么？
- Harness 各层级中哪一层对 Agent 质量影响最大？为什么？
