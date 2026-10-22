import assert from 'node:assert/strict';
import test from 'node:test';

import { StreamHttpError, streamSse } from './stream.ts';

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

test('SSE 建连阶段的 HTTP 错误保留状态码和服务端消息', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(
    JSON.stringify({ code: 400, message: '请求参数不合法', data: null }),
    { status: 400, headers: { 'content-type': 'application/json' } },
  );

  const errors: Error[] = [];
  try {
    await streamSse({
      url: '/agent',
      init: { method: 'POST' },
      onMessage: () => undefined,
      onComplete: () => undefined,
      onError: (error) => errors.push(error),
    });
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.equal(errors.length, 1);
  assert.ok(errors[0] instanceof StreamHttpError);
  assert.equal((errors[0] as StreamHttpError).status, 400);
  assert.equal(errors[0].message, '请求参数不合法');
});

test('SSE 解析异常时主动取消底层读取器', async () => {
  const originalFetch = globalThis.fetch;
  let cancelled = 0;
  globalThis.fetch = async () => new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(`data: ${agentErrorPayload}\n\n`));
      },
      cancel() {
        cancelled += 1;
      },
    }),
    { status: 200, headers: { 'content-type': 'text/event-stream' } },
  );

  const errors: Error[] = [];
  try {
    await streamSse({
      url: '/rag',
      init: { method: 'POST' },
      parseMode: 'event',
      onMessage: () => undefined,
      onComplete: () => undefined,
      onError: (error) => errors.push(error),
    });
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.equal(errors.length, 1);
  assert.equal(cancelled, 1);
});
