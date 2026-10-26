CREATE TABLE IF NOT EXISTS agent_audit_events (
  id BIGSERIAL PRIMARY KEY,
  owner_key VARCHAR(64),
  interview_session_id VARCHAR(64),
  agent_session_id VARCHAR(64),
  run_id VARCHAR(64),
  action VARCHAR(64) NOT NULL,
  resource_type VARCHAR(32) NOT NULL,
  resource_id VARCHAR(128),
  result VARCHAR(32),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_audit_owner_created
  ON agent_audit_events (owner_key, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_audit_session_created
  ON agent_audit_events (agent_session_id, created_at DESC);

COMMENT ON TABLE agent_audit_events IS 'Agent 低敏操作审计事件，不保存 Prompt、回答和 API Key';
