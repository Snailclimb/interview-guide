import assert from 'node:assert/strict';
import test from 'node:test';

import { normalizeAgentMarkdown } from './agentMarkdown.ts';

test('将已知标题和正文拆成可读的 Markdown 段落', () => {
  const result = normalizeAgentMarkdown(
    '## 本场面试复盘结论 当前只能做**材料完整性复盘**。## 本场面试考察重点 1. **Redis 缓存设计** - Key 设计',
  );

  assert.match(result, /## 本场面试复盘结论\n\n当前只能做/);
  assert.match(result, /复盘\*\*。\n\n## 本场面试考察重点/);
  assert.match(result, /1\. \*\*Redis 缓存设计\*\*/);
  assert.match(result, /\n- Key 设计/);
});

test('未预置的二级标题也不会粘在上一段正文后面', () => {
  const result = normalizeAgentMarkdown('上一段结论。 ## 风险与限制 需要继续验证。');

  assert.equal(result, '上一段结论。\n\n## 风险与限制 需要继续验证。');
});

test('代码围栏中的标题和列表保持原样', () => {
  const source = '## 结论\n\n```md\n## 不要拆分\n- keep this line\n```';

  assert.equal(normalizeAgentMarkdown(source), source);
});

test('兼容 Windows 换行符', () => {
  assert.equal(normalizeAgentMarkdown('第一段\r\n\r\n第二段'), '第一段\n\n第二段');
});
