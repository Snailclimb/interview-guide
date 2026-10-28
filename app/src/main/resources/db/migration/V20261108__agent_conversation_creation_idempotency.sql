CREATE TABLE agent_conversation_creation_requests (
    id BIGSERIAL PRIMARY KEY,
    owner_key VARCHAR(64) NOT NULL,
    interview_session_id VARCHAR(36) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    agent_session_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_conversation_creation_owner_key UNIQUE (owner_key, idempotency_key),
    CONSTRAINT uq_agent_conversation_creation_agent_session UNIQUE (agent_session_id)
);

CREATE INDEX idx_agent_conversation_creation_session
    ON agent_conversation_creation_requests (owner_key, interview_session_id, created_at DESC);

COMMENT ON TABLE agent_conversation_creation_requests IS
    'Agent 对话创建请求幂等记录；同一用户和请求键只创建一个线程';
