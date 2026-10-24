ALTER TABLE agent_chat_sessions
  ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_agent_chat_active_interview_updated
  ON agent_chat_sessions (interview_session_id, archived, updated_at DESC);
