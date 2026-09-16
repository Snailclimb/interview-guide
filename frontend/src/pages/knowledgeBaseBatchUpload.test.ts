import assert from 'node:assert/strict';
import test from 'node:test';

import {
  getFileIdentity,
  MAX_BATCH_FILES,
  MAX_CONCURRENT_UPLOADS,
  runWithConcurrency,
  toBatchUploadStatus,
  validateKnowledgeBaseFile,
} from './knowledgeBaseBatchUpload.ts';

test('文件校验限制格式、空文件和 50MB 大小', () => {
  assert.equal(validateKnowledgeBaseFile({ name: 'guide.pdf', size: 1024 }), null);
  assert.equal(validateKnowledgeBaseFile({ name: 'guide.MD', size: 1024 }), null);
  assert.equal(validateKnowledgeBaseFile({ name: 'empty.txt', size: 0 }), '文件为空');
  assert.equal(
    validateKnowledgeBaseFile({ name: 'large.docx', size: 50 * 1024 * 1024 + 1 }),
    '文件超过 50MB',
  );
  assert.equal(
    validateKnowledgeBaseFile({ name: 'archive.zip', size: 1024 }),
    '仅支持 PDF、DOCX、DOC、TXT、MD',
  );
});

test('文件标识可过滤同一次选择中的重复文件', () => {
  const first = { name: 'guide.pdf', size: 1024, lastModified: 1 };
  const same = { name: 'guide.pdf', size: 1024, lastModified: 1 };
  const changed = { name: 'guide.pdf', size: 2048, lastModified: 2 };

  assert.equal(getFileIdentity(first), getFileIdentity(same));
  assert.notEqual(getFileIdentity(first), getFileIdentity(changed));
  assert.equal(MAX_BATCH_FILES, 10);
});

test('上传队列的同时执行数不会超过 2', async () => {
  let active = 0;
  let maximumActive = 0;

  await runWithConcurrency([1, 2, 3, 4, 5], MAX_CONCURRENT_UPLOADS, async () => {
    active += 1;
    maximumActive = Math.max(maximumActive, active);
    await new Promise(resolve => setTimeout(resolve, 10));
    active -= 1;
  });

  assert.equal(maximumActive, 2);
});

test('后端向量化失败映射为可重试的前端状态', () => {
  assert.equal(toBatchUploadStatus('PENDING'), 'PENDING');
  assert.equal(toBatchUploadStatus('PROCESSING'), 'PROCESSING');
  assert.equal(toBatchUploadStatus('COMPLETED'), 'COMPLETED');
  assert.equal(toBatchUploadStatus('FAILED'), 'VECTOR_FAILED');
});
