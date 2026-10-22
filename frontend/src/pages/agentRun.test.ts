import assert from 'node:assert/strict';
import test from 'node:test';

import { getRetryableRequest, removeLastRunMessages, type AgentRunRequest } from './agentRun.ts';

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

test('页面刷新后从失败会话恢复原始请求', () => {
  assert.deepEqual(getRetryableRequest({
    interviewSessionId: 'session-1',
    goal: 'practice',
    providerId: 'backup',
    runId: 'run-1',
    runStatus: 'FAILED',
    runStartedAt: null,
    runFinishedAt: null,
    runError: '模型不可用',
    messages: [
      { role: 'user', content: '之前的问题' },
      { role: 'assistant', content: '之前的回答' },
      { role: 'user', content: '这次失败的问题' },
    ],
  }), {
    goal: 'practice',
    message: '这次失败的问题',
    providerId: 'backup',
  });
});

test('没有失败状态或没有用户消息时不生成重试请求', () => {
  assert.equal(getRetryableRequest({
    interviewSessionId: 'session-1',
    goal: 'review',
    providerId: null,
    runId: null,
    runStatus: 'COMPLETED',
    runStartedAt: null,
    runFinishedAt: null,
    runError: null,
    messages: [],
  }), null);
});
