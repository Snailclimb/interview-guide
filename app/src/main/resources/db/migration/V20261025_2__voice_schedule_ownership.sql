ALTER TABLE interview_schedule ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);

CREATE INDEX IF NOT EXISTS idx_interview_schedule_owner_time
  ON interview_schedule (owner_id, interview_time);

CREATE INDEX IF NOT EXISTS idx_voice_interview_session_user
  ON voice_interview_sessions (user_id, updated_at DESC);
