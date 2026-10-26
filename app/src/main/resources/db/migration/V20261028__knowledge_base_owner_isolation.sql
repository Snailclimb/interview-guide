-- 登录用户上传的知识库与 RAG 会话按 owner_id 隔离。
-- NULL owner 保留为历史部署级共享数据，避免升级后现有题库不可用。
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS owner_id VARCHAR(64);
ALTER TABLE rag_chat_sessions ADD COLUMN IF NOT EXISTS owner_id VARCHAR(64);

ALTER TABLE knowledge_bases DROP CONSTRAINT IF EXISTS idx_kb_hash;
DROP INDEX IF EXISTS idx_kb_hash;

CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_owner_hash
    ON knowledge_bases (owner_id, file_hash);
CREATE INDEX IF NOT EXISTS idx_kb_owner
    ON knowledge_bases (owner_id);
CREATE INDEX IF NOT EXISTS idx_rag_chat_owner
    ON rag_chat_sessions (owner_id);
