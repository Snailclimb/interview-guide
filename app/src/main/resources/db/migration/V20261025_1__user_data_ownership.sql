ALTER TABLE resumes ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);
ALTER TABLE interview_sessions ADD COLUMN IF NOT EXISTS owner_id VARCHAR(36);

ALTER TABLE resumes DROP CONSTRAINT IF EXISTS idx_resume_hash;
ALTER TABLE resumes DROP CONSTRAINT IF EXISTS resumes_file_hash_key;
CREATE UNIQUE INDEX IF NOT EXISTS idx_resume_owner_hash ON resumes (owner_id, file_hash);
CREATE INDEX IF NOT EXISTS idx_resume_owner_uploaded ON resumes (owner_id, uploaded_at);
CREATE INDEX IF NOT EXISTS idx_interview_session_owner_created
  ON interview_sessions (owner_id, created_at DESC);
