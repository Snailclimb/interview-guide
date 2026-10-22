import assert from 'node:assert/strict';
import test from 'node:test';

import { isNearChatBottom } from './agentChatScroll.ts';

test('对话初始或已经接近底部时允许自动跟随', () => {
  assert.equal(isNearChatBottom(0, 480, 480), true);
  assert.equal(isNearChatBottom(424, 480, 1000), true);
  assert.equal(isNearChatBottom(419, 480, 1000), false);
});

test('用户上滑超过阈值后不抢回滚动位置', () => {
  assert.equal(isNearChatBottom(300, 480, 1000, 96), false);
});

test('滚动尺寸异常时仍不会把用户从顶部拉到底部', () => {
  assert.equal(isNearChatBottom(100, 480, 500), true);
  assert.equal(isNearChatBottom(0, 480, 2000), false);
});

test('新任务开始时可以恢复跟随策略', () => {
  assert.equal(isNearChatBottom(0, 480, 2000), false);
  // 新任务由页面把 follow 状态重置为 true，之后下一次消息变化即可滚到底部。
  assert.equal(isNearChatBottom(1424, 480, 2000), true);
});
