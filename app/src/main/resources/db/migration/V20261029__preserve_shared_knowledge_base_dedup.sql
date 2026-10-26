-- owner 隔离后，历史共享知识库仍保持原来的全局去重语义。
-- 私有知识库使用 V20261028 的 (owner_id, file_hash) 唯一索引，
-- NULL owner 使用本部分索引继续禁止同一共享文件重复上传。
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_shared_hash
    ON knowledge_bases (file_hash)
    WHERE owner_id IS NULL;
