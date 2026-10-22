import assert from 'node:assert/strict';
import test from 'node:test';

import { removeLastRunMessages, type AgentRunRequest } from './agentRun.ts';

const request: AgentRunRequest = {
  goal: 'review',
  message: '请分析 Redis 这一题',
  providerId: 'primary',
};

test('重试时按消息 ID 移除上一次用户和助手临时消息', () => {
  const messages = [
    { id: 1, role: 'assistant' as const, content: '历史回答' },
    { id: 10, role: 'user' as const, content: request.message },
    { id: 11, role: 'assistant' as const, content: '模型暂时不可用' },
  ];

  assert.deepEqual(removeLastRunMessages(messages, request, {
    userMessageId: 10,
    assistantMessageId: 11,
  }), [messages[0]]);
});

test('恢复会话后没有消息 ID 时仍能移除末尾失败请求', () => {
  const messages = [
    { id: 1, role: 'assistant' as const, content: '历史回答' },
    { id: 2, role: 'user' as const, content: request.message },
    { id: 3, role: 'assistant' as const, content: 'Agent 连接暂时中断' },
  ];

  assert.deepEqual(removeLastRunMessages(messages, request), [messages[0]]);
});

test('末尾不是该请求时不误删正常历史', () => {
  const messages = [
    { id: 1, role: 'user' as const, content: '其他问题' },
    { id: 2, role: 'assistant' as const, content: '正常回答' },
  ];

  assert.deepEqual(removeLastRunMessages(messages, request), messages);
});
