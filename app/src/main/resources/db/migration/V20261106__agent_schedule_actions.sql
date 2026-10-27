ALTER TABLE agent_action_requests
    ADD COLUMN IF NOT EXISTS schedule_company_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS schedule_position VARCHAR(255),
    ADD COLUMN IF NOT EXISTS schedule_interview_time TIMESTAMP,
    ADD COLUMN IF NOT EXISTS schedule_interview_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS schedule_meeting_link TEXT,
    ADD COLUMN IF NOT EXISTS schedule_round_number INTEGER,
    ADD COLUMN IF NOT EXISTS schedule_interviewer VARCHAR(255),
    ADD COLUMN IF NOT EXISTS schedule_notes TEXT;

ALTER TABLE interview_schedule
    ADD COLUMN IF NOT EXISTS agent_action_id VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_interview_schedule_agent_action
    ON interview_schedule (agent_action_id)
    WHERE agent_action_id IS NOT NULL;

COMMENT ON COLUMN agent_action_requests.schedule_interview_time IS
    '仅用于已确认的 Agent 复习日程动作；未确认时不会写入 interview_schedule';
