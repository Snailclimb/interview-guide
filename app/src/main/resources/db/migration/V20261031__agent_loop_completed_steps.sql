ALTER TABLE agent_chat_sessions
  ADD COLUMN IF NOT EXISTS loop_completed_steps TEXT;

COMMENT ON COLUMN agent_chat_sessions.loop_completed_steps IS
  '已完成的 Agent Loop 步骤索引，用于并行组部分成功后的跨重启恢复';
