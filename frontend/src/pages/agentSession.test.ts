import assert from 'node:assert/strict';
import test from 'node:test';

import { resolveAgentSessionId, resolveAgentThreadId } from './agentSession.ts';

test('URL 中的面试会话优先于页面保存的会话', () => {
  assert.equal(resolveAgentSessionId('from-url', 'from-storage'), 'from-url');
});

test('从导航返回 Agent 页面时恢复最近一次面试会话', () => {
  assert.equal(resolveAgentSessionId(null, 'last-session'), 'last-session');
});

test('没有 URL 和保存值时保持空白上下文', () => {
  assert.equal(resolveAgentSessionId(null, null), null);
});

test('补回保存的面试报告参数时继续恢复同一 Agent 线程', () => {
  assert.equal(
    resolveAgentThreadId('last-session', null, 'last-session', 'thread-b'),
    'thread-b',
  );
});

test('明确打开另一份面试报告时不串用上一份 Agent 线程', () => {
  assert.equal(
    resolveAgentThreadId('another-session', null, 'last-session', 'thread-b'),
    null,
  );
});

test('URL 中明确指定的 Agent 线程优先', () => {
  assert.equal(
    resolveAgentThreadId('last-session', 'thread-url', 'last-session', 'thread-storage'),
    'thread-url',
  );
});
