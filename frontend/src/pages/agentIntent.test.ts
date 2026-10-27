import assert from 'node:assert/strict';
import test from 'node:test';

import { detectAgentActionIntent, suggestScheduleTime } from './agentIntent.ts';

const now = new Date(2026, 9, 27, 10, 15);

test('识别安排下周复习的自然语言意图并预填下周一晚上', () => {
  const intent = detectAgentActionIntent('帮我安排下周复习 Redis', now);
  assert.equal(intent?.type, 'schedule');
  assert.equal(intent?.suggestedTime, suggestScheduleTime('下周', now));
  assert.equal(intent?.suggestedTime, '2026-11-02T19:00');
});

test('识别明天下午三点的复习时间', () => {
  assert.equal(suggestScheduleTime('明天下午3点复习', now), '2026-10-28T15:00');
});

test('普通复盘请求和取消安排不会误触发动作', () => {
  assert.equal(detectAgentActionIntent('请复盘一下这场面试'), null);
  assert.equal(detectAgentActionIntent('不要安排复习日程'), null);
  assert.equal(detectAgentActionIntent('帮我预约面试'), null);
  assert.equal(detectAgentActionIntent('不要保存到长期记忆'), null);
  assert.equal(detectAgentActionIntent('不要导出面试报告'), null);
});

test('识别训练任务、记忆、标题、报告和简历动作', () => {
  assert.deepEqual(detectAgentActionIntent('帮我创建一个训练任务，重点练 Redis'), { type: 'trainingTask' });
  assert.deepEqual(detectAgentActionIntent('把这个薄弱点保存到长期记忆'), { type: 'memory' });
  assert.deepEqual(detectAgentActionIntent('给这场面试起个名字'), { type: 'title' });
  assert.deepEqual(detectAgentActionIntent('请导出这场面试报告 PDF'), { type: 'reportExport' });
  assert.deepEqual(detectAgentActionIntent('帮我优化简历'), { type: 'resumeEdit' });
});
