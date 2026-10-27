ALTER TABLE agent_action_requests
    ADD COLUMN IF NOT EXISTS memory_category VARCHAR(32),
    ADD COLUMN IF NOT EXISTS memory_job_scope VARCHAR(64),
    ADD COLUMN IF NOT EXISTS target_title VARCHAR(120);
