import assert from 'node:assert/strict';
import test from 'node:test';

import { streamSse } from './stream.ts';

const agentErrorPayload = JSON.stringify({
  type: 'error',
  message: '当前模型服务繁忙，请稍后重试',
});

function mockSseResponse(body: string): Response {
  return new Response(body, {
    status: 200,
    headers: { 'content-type': 'text/event-stream' },
  });
}

test('Agent 业务错误事件交给调用方处理，不被解析器误判为连接异常', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => mockSseResponse(
    `event: agent-error\ndata: ${agentErrorPayload}\n\n`,
  );

  const received: string[] = [];
  const errors: Error[] = [];
  let completed = 0;
  try {
    await streamSse({
      url: '/agent',
      init: { method: 'POST' },
      parseMode: 'event',
      trimDataPrefixSpace: true,
      onMessage: (chunk) => received.push(chunk),
      onComplete: () => { completed += 1; },
      onError: (error) => errors.push(error),
      preserveBusinessErrorEvents: true,
    });
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.deepEqual(received, [agentErrorPayload]);
  assert.equal(completed, 1);
  assert.deepEqual(errors, []);
});

test('普通流仍将业务错误作为解析异常处理', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => mockSseResponse(
    `data: ${agentErrorPayload}\n\n`,
  );

  const errors: Error[] = [];
  try {
    await streamSse({
      url: '/rag',
      init: { method: 'POST' },
      parseMode: 'event',
      trimDataPrefixSpace: false,
      onMessage: () => undefined,
      onComplete: () => undefined,
      onError: (error) => errors.push(error),
    });
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.equal(errors.length, 1);
  assert.equal(errors[0].message, '当前模型服务繁忙，请稍后重试');
});
