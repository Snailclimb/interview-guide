ALTER TABLE agent_chat_sessions
  ADD COLUMN agent_session_id VARCHAR(36),
  ADD COLUMN title VARCHAR(120);

UPDATE agent_chat_sessions
SET agent_session_id = public.uuid_generate_v4()::varchar,
    title = COALESCE(NULLIF(title, ''), '默认 Agent 对话')
WHERE agent_session_id IS NULL;

ALTER TABLE agent_chat_sessions
  ALTER COLUMN agent_session_id SET NOT NULL,
  ALTER COLUMN title SET DEFAULT '新 Agent 对话';

ALTER TABLE agent_chat_sessions
  DROP CONSTRAINT uk_agent_chat_interview_session;

ALTER TABLE agent_chat_sessions
  ADD CONSTRAINT uk_agent_chat_session_id UNIQUE (agent_session_id);

CREATE INDEX idx_agent_chat_interview_session
  ON agent_chat_sessions (interview_session_id, updated_at DESC);
