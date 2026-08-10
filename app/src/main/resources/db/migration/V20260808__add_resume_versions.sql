-- 简历版本迭代：支持在简历详情页直接上传优化后的新版本
-- 版本族（version group）：首版本 version_group_id 为 NULL（以自身 id 为根），
-- 后续版本共享首版本 id 作为 version_group_id，version_no 从 1 递增，parent_id 指向直接父版本。

ALTER TABLE resumes ADD COLUMN version_group_id BIGINT;
ALTER TABLE resumes ADD COLUMN version_no INTEGER NOT NULL DEFAULT 1;
ALTER TABLE resumes ADD COLUMN parent_id BIGINT;
ALTER TABLE resumes ADD COLUMN version_note VARCHAR(500);

CREATE INDEX idx_resumes_version_group ON resumes (version_group_id);
CREATE INDEX idx_resumes_parent ON resumes (parent_id);
