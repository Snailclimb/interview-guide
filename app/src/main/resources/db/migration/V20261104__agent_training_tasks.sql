CREATE TABLE IF NOT EXISTS agent_training_tasks (
    id BIGSERIAL PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL UNIQUE,
    owner_key VARCHAR(64) NOT NULL,
    source_interview_session_id VARCHAR(64) NOT NULL,
    source_agent_session_id VARCHAR(64) NOT NULL,
    source_action_id VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_training_task_owner_status_created
    ON agent_training_tasks (owner_key, status, created_at DESC);

COMMENT ON TABLE agent_training_tasks IS
    'Agent 确认后生成的用户训练任务；与原始面试报告隔离';
