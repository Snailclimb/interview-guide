ALTER TABLE agent_chat_sessions
  ADD COLUMN owner_key VARCHAR(64);

UPDATE agent_chat_sessions
SET owner_key = NULLIF(memory_key, '')
WHERE owner_key IS NULL;

CREATE INDEX idx_agent_chat_owner_interview_updated
  ON agent_chat_sessions (owner_key, interview_session_id, updated_at DESC);
