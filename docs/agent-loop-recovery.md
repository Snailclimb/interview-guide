# Agent Loop 任务恢复设计

## 目标

应用重启或实例接管时，Agent 不再无条件从 Loop 第一步重新执行。系统只恢复可验证的
计划文本和下一个待执行步骤；当前面试报告、Skill/RAG 上下文和最终模型回答仍按原有
流程重新建立。

## 持久化状态

- `agent_chat_sessions.loop_plan`：最近一次已生成的有限计划；
- `agent_chat_sessions.loop_next_step`：下一个待执行步骤的零基索引；
- 状态通过 `AgentProgressWriter` 合并写入，并在终态前刷盘；
- 计划没有写入用户问答，也不保存隐藏推理或完整 RAG 原文。

## 恢复流程

1. 恢复扫描锁定过期 `RUNNING` 会话，换发新的 `runId`；
2. 保留已持久化的 Loop 计划和步骤索引，清理未完成助手占位消息；
3. `InterviewAgentService` 复用原计划，调用 `AgentLoopEngine.resume` 跳过已完成步骤；并行组部分成功时只重跑失败兄弟步骤，
   不重复已经成功的只读观察；
4. 继续走现有上下文、来源校验、模型重试和终态持久化流程；
5. 没有计划、索引非法或状态不完整时，安全回退到从头执行。

## 边界

Loop 状态只是恢复提示，不是分布式锁；会话级数据库锁和新的 `runId` 仍负责防止多实例
重复接管。恢复不会复用旧的模型连接、SSE 连接或隐藏推理。故障步骤会从该步骤重新
执行，已完成步骤不会重复执行。检查点索引必须落在当前计划范围内，越界或损坏时安全回退到计划起点，
不将异常状态解释为整轮已完成。

## 验证

- `AgentConversationRecoveryTest` 验证计划和步骤索引会随过期任务被接管；
- `AgentProgressWriterTest` 验证终态前会刷出 Loop 恢复状态；
- `./gradlew :app:test --no-daemon` 验证完整后端回归。
