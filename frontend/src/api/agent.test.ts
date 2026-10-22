import assert from 'node:assert/strict';
import test from 'node:test';

import { agentApi } from './agent.ts';

test('Agent 收到无法解析的 SSE 事件时立即结束流，不触发完成回调', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response(
    'event: agent-delta\ndata: {not-json}\n\n',
    { status: 200, headers: { 'content-type': 'text/event-stream' } },
  );

  const errors: Error[] = [];
  let completed = 0;
  try {
    await agentApi.streamMessage(
      'interview-1',
      { goal: 'review', message: '请复盘', history: [] },
      {
        onEvent: () => undefined,
        onComplete: () => { completed += 1; },
        onError: (error) => errors.push(error),
      },
    );
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.equal(completed, 0);
  assert.equal(errors.length, 1);
  assert.equal(errors[0].message, 'Agent 返回了无法识别的事件');
});
