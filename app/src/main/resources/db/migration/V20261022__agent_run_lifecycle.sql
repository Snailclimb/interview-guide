ALTER TABLE agent_chat_sessions
  ADD COLUMN run_id VARCHAR(36),
  ADD COLUMN run_status VARCHAR(16) NOT NULL DEFAULT 'IDLE',
  ADD COLUMN active_message_id BIGINT,
  ADD COLUMN run_started_at TIMESTAMP(6),
  ADD COLUMN run_finished_at TIMESTAMP(6),
  ADD COLUMN run_error VARCHAR(500);

ALTER TABLE agent_chat_sessions
  ADD CONSTRAINT agent_chat_sessions_run_status_check
  CHECK (run_status IN ('IDLE', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'));

CREATE INDEX idx_agent_chat_sessions_run_status
  ON agent_chat_sessions (run_status, run_started_at);
