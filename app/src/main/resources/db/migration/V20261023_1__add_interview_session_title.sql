ALTER TABLE interview_sessions
    ADD COLUMN IF NOT EXISTS title VARCHAR(120);

COMMENT ON COLUMN interview_sessions.title IS '用户自定义的面试记录名称';
