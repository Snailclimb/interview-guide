import assert from 'node:assert/strict';
import test from 'node:test';

import { resolveAgentSessionId } from './agentSession.ts';

test('URL 中的面试会话优先于页面保存的会话', () => {
  assert.equal(resolveAgentSessionId('from-url', 'from-storage'), 'from-url');
});

test('从导航返回 Agent 页面时恢复最近一次面试会话', () => {
  assert.equal(resolveAgentSessionId(null, 'last-session'), 'last-session');
});

test('没有 URL 和保存值时保持空白上下文', () => {
  assert.equal(resolveAgentSessionId(null, null), null);
});
