ALTER TABLE agent_chat_sessions
  ADD COLUMN run_step VARCHAR(32),
  ADD COLUMN run_step_status VARCHAR(16),
  ADD COLUMN run_notice VARCHAR(500),
  ADD COLUMN run_sources TEXT;

CREATE INDEX idx_agent_chat_sessions_run_progress
  ON agent_chat_sessions (run_status, run_step, run_step_status);
