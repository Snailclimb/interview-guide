CREATE TABLE IF NOT EXISTS agent_runs (
  run_id VARCHAR(36) NOT NULL,
  agent_type VARCHAR(40) NOT NULL,
  business_session_id VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_fingerprint VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  version BIGINT,
  CONSTRAINT pk_agent_runs PRIMARY KEY (run_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_run_idempotency_key
  ON agent_runs (idempotency_key);
