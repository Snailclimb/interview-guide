import assert from 'node:assert/strict';
import test from 'node:test';

import {
  canRetryUpload,
  getFileIdentity,
  MAX_BATCH_FILES,
  MAX_CONCURRENT_UPLOADS,
  RateLimitedUploadQueue,
  selectKnowledgeBaseFiles,
  toBatchUploadStatus,
  UPLOAD_RETRY_COOLDOWN_MS,
  UPLOAD_START_INTERVAL_MS,
  validateKnowledgeBaseFile,
} from './knowledgeBaseBatchUpload.ts';

const delay = (milliseconds: number) => new Promise(resolve => setTimeout(resolve, milliseconds));
const file = (name: string, size = 1024, lastModified = 1) => (
  { name, size, lastModified } as File
);

test('文件校验限制格式、空文件和 50MB 大小', () => {
  assert.equal(validateKnowledgeBaseFile(file('guide.pdf')), null);
  assert.equal(validateKnowledgeBaseFile(file('guide.MD')), null);
  assert.equal(validateKnowledgeBaseFile(file('empty.txt', 0)), '文件为空');
  assert.equal(validateKnowledgeBaseFile(file('large.docx', 50 * 1024 * 1024 + 1)), '文件超过 50MB');
  assert.equal(validateKnowledgeBaseFile(file('archive.zip')), '仅支持 PDF、DOCX、DOC、TXT、MD');
});

test('连续选择共用列表去重和 10 个文件上限', () => {
  const firstSelection = selectKnowledgeBaseFiles([], [file('guide.pdf')], 1);
  const secondSelection = selectKnowledgeBaseFiles(firstSelection.accepted, [
    file('guide.pdf'),
    ...Array.from({ length: 10 }, (_, index) => file(`${index}.txt`, 1024, index + 2)),
  ], 2);

  assert.equal(firstSelection.accepted[0]?.status, 'READY');
  assert.equal(secondSelection.accepted.length, MAX_BATCH_FILES - 1);
  assert.match(secondSelection.rejected[0]!, /已在列表中/);
  assert.match(secondSelection.rejected[secondSelection.rejected.length - 1]!, /单批最多 10 个文件/);
  assert.equal(getFileIdentity(file('guide.pdf')), getFileIdentity(file('guide.pdf')));
});

test('共享队列限制并发、发送节奏并接收运行中追加任务', async () => {
  const queue = new RateLimitedUploadQueue(MAX_CONCURRENT_UPLOADS, 25);
  const starts: number[] = [];
  const order: string[] = [];
  let active = 0;
  let maximumActive = 0;
  const task = (clientId: string) => async () => {
    starts.push(Date.now());
    order.push(clientId);
    active += 1;
    maximumActive = Math.max(maximumActive, active);
    await delay(70);
    active -= 1;
  };

  queue.enqueue('1', task('1'));
  queue.enqueue('2', task('2'));
  queue.enqueue('3', task('3'));
  await delay(10);
  assert.equal(queue.enqueue('4', task('4')), true);
  assert.equal(queue.enqueue('4', task('duplicate')), false);
  await queue.whenIdle();

  assert.equal(maximumActive, MAX_CONCURRENT_UPLOADS);
  assert.deepEqual(order, ['1', '2', '3', '4']);
  starts.slice(1).forEach((startedAt, index) => {
    assert.ok(startedAt - starts[index]! >= 20);
  });
  queue.dispose();
});

test('单项失败不会阻断队列中的后续任务', async () => {
  const queue = new RateLimitedUploadQueue(1, 0);
  const completed: string[] = [];

  queue.enqueue('failed', async () => {
    throw new Error('上传失败');
  });
  queue.enqueue('completed', async () => {
    completed.push('completed');
  });
  await queue.whenIdle();

  assert.deepEqual(completed, ['completed']);
  queue.dispose();
});

test('上传和向量化状态、生产限制保持独立', () => {
  assert.equal(toBatchUploadStatus('PENDING'), 'PENDING');
  assert.equal(toBatchUploadStatus('PROCESSING'), 'PROCESSING');
  assert.equal(toBatchUploadStatus('COMPLETED'), 'COMPLETED');
  assert.equal(toBatchUploadStatus('FAILED'), 'VECTOR_FAILED');
  assert.equal(UPLOAD_START_INTERVAL_MS, 500);
  assert.equal(UPLOAD_RETRY_COOLDOWN_MS, 2000);
  assert.equal(canRetryUpload({ status: 'UPLOAD_FAILED', retryAvailableAt: 3000 }, 2999), false);
  assert.equal(canRetryUpload({ status: 'UPLOAD_FAILED', retryAvailableAt: 3000 }, 3000), true);
});
