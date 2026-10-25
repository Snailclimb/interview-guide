ALTER TABLE agent_chat_sessions
  ADD COLUMN loop_plan TEXT,
  ADD COLUMN loop_next_step INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN agent_chat_sessions.loop_plan IS '最近一次 Agent Loop 计划，用于任务恢复';
COMMENT ON COLUMN agent_chat_sessions.loop_next_step IS '下一个待执行的 Loop 步骤索引，从 0 开始';
