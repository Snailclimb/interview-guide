CREATE TABLE IF NOT EXISTS agent_action_requests (
    id BIGSERIAL PRIMARY KEY,
    action_id VARCHAR(64) NOT NULL UNIQUE,
    owner_key VARCHAR(64) NOT NULL,
    interview_session_id VARCHAR(64) NOT NULL,
    agent_session_id VARCHAR(64) NOT NULL,
    tool_id VARCHAR(64) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(120) NOT NULL,
    preview_text TEXT NOT NULL,
    draft_content TEXT NOT NULL,
    result_summary VARCHAR(500),
    idempotency_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT uq_agent_action_owner_idempotency UNIQUE (owner_key, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_agent_action_owner_session_created
    ON agent_action_requests (owner_key, interview_session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_action_agent_session_created
    ON agent_action_requests (agent_session_id, created_at DESC);

COMMENT ON TABLE agent_action_requests IS
    'Agent 用户确认动作与草稿；不直接覆盖原始业务数据';
