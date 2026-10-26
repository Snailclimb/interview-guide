import { expect, test } from '@playwright/test';

const envelope = <T>(data: T) => JSON.stringify({ code: 200, message: 'success', data });

const sessions = [{
  sessionId: 'interview-1',
  skillId: 'java-backend',
  title: 'Java 后端高并发面试',
  difficulty: 'mid',
  resumeId: null,
  totalQuestions: 3,
  status: 'COMPLETED',
  evaluateStatus: 'COMPLETED',
  evaluateError: null,
  overallScore: 78,
  sourceType: 'NORMAL',
  knowledgeBaseId: null,
  interviewCategory: null,
  createdAt: '2026-10-24T09:00:00',
  completedAt: '2026-10-24T09:30:00',
}];

const interview = {
  ...sessions[0],
  overallFeedback: '具备较好的后端基础。',
  questions: [],
  strengths: ['表达清晰'],
  improvements: ['补充故障定位案例'],
  referenceAnswers: [],
  answers: [{
    questionIndex: 0,
    question: '如何设计 Redis 缓存？',
    category: 'Redis',
    userAnswer: '使用 TTL 和缓存更新策略。',
    score: 78,
    feedback: '回答基本完整。',
    answeredAt: '2026-10-24T09:10:00',
  }],
};

function conversation(agentSessionId: string, title: string, text: string) {
  return {
    interviewSessionId: 'interview-1',
    agentSessionId,
    title,
    goal: 'review',
    providerId: 'pixel',
    runId: null,
    runStatus: 'IDLE',
    runStartedAt: null,
    runFinishedAt: null,
    runError: null,
    runStep: null,
    runStepStatus: null,
    runNotice: null,
    runPartialAnswer: null,
    runSources: [],
    messages: [
      { role: 'user', content: '请复盘这场面试' },
      { role: 'assistant', content: text },
    ],
  };
}

test.describe('AI 面试教练 Agent 会话与 Loop', () => {
  test.beforeEach(async ({ page }) => {
    // Agent 页面在正式环境由 AuthGate 保护；所有业务接口虽由本用例 mock，
    // 认证探针也必须返回已登录状态，否则用例会被重定向到登录页而无法覆盖 Agent UI。
    await page.route('**/api/auth/me', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: envelope({
          required: true,
          authenticated: true,
          user: {
            id: 'e2e-user',
            email: 'agent-e2e@example.com',
            nickname: 'Agent E2E',
            status: 'ACTIVE',
            emailVerified: false,
            lastLoginAt: null,
          },
        }),
      });
    });
    const threadA = conversation('thread-a', '第一轮复盘', '线程 A 的复盘内容');
    const threadB = conversation('thread-b', '第二轮训练', '线程 B 的训练内容');
    let conversationList = [
      { agentSessionId: 'thread-a', interviewSessionId: 'interview-1', title: '第一轮复盘', runStatus: 'IDLE', createdAt: '2026-10-24T09:01:00', updatedAt: '2026-10-24T09:02:00' },
      { agentSessionId: 'thread-b', interviewSessionId: 'interview-1', title: '第二轮训练', runStatus: 'IDLE', createdAt: '2026-10-24T09:03:00', updatedAt: '2026-10-24T09:04:00' },
    ];
    let memory = {
      lastSkillId: 'java-backend',
      weaknesses: ['Redis 缓存'],
      recentGoals: ['practice'],
      updatedAt: '2026-10-24T09:05:00',
      items: [{
        id: 'memory-1', category: 'weakness', content: 'Redis 缓存', source: 'interview-report',
        jobScope: 'java-backend', confidence: 0.9, updatedAt: '2026-10-24T09:05:00', expiresAt: '2027-04-22T09:05:00',
      }],
    };

    await page.route('**/api/interview/sessions', async route => {
      await route.fulfill({ contentType: 'application/json', body: envelope(sessions) });
    });
    await page.route('**/api/interview/sessions/interview-1/details', async route => {
      await route.fulfill({ contentType: 'application/json', body: envelope(interview) });
    });
    await page.route('**/api/llm-provider/list', async route => {
      await route.fulfill({ contentType: 'application/json', body: envelope([{
        id: 'pixel', baseUrl: 'https://example.test', maskedApiKey: '***', model: 'gpt-5.6-luna',
        embeddingModel: null, embeddingDimensions: null, supportsEmbedding: false, temperature: 0.2,
        defaultChatProvider: true, defaultEmbeddingProvider: false,
      }]) });
    });
    await page.route('**/api/llm-provider/default-provider', async route => {
      await route.fulfill({ contentType: 'application/json', body: envelope({ defaultProvider: 'pixel', defaultEmbeddingProvider: '' }) });
    });
    await page.route('**/api/agent/memory', async route => {
      if (route.request().method() === 'DELETE') {
        memory = { lastSkillId: null, weaknesses: [], recentGoals: [], updatedAt: null, items: [] };
      }
      await route.fulfill({ contentType: 'application/json', body: envelope(memory) });
    });
    await page.route('**/api/agent/audit/events*', async route => {
      await route.fulfill({ contentType: 'application/json', body: envelope([
        {
          interviewSessionId: 'interview-1', agentSessionId: 'thread-a',
          action: 'RUN_COMPLETED', resourceType: 'agent-run', resourceId: 'run-1',
          result: 'COMPLETED', createdAt: '2026-10-24T09:06:00',
        },
      ]) });
    });
    await page.route('**/api/agent/memory/items/*', async route => {
      const itemId = route.request().url().split('/').pop()!;
      if (route.request().method() === 'PATCH') {
        const body = route.request().postDataJSON() as { content: string; jobScope?: string };
        memory = {
          ...memory,
          weaknesses: memory.weaknesses.map(value => value === 'Redis 缓存' ? body.content : value),
          items: memory.items.map(item => item.id === itemId
            ? { ...item, content: body.content, jobScope: body.jobScope || null }
            : item),
        };
      } else if (route.request().method() === 'DELETE') {
        memory = { ...memory, items: memory.items.filter(item => item.id !== itemId) };
      }
      await route.fulfill({ contentType: 'application/json', body: envelope(memory) });
    });
    await page.route('**/api/agent/interview-sessions/interview-1/conversations', async route => {
      if (route.request().method() === 'POST') {
        const created = { agentSessionId: 'thread-new', interviewSessionId: 'interview-1', title: '新 Agent 对话', runStatus: 'IDLE', createdAt: '2026-10-24T09:05:00', updatedAt: '2026-10-24T09:05:00' };
        conversationList = [...conversationList, created];
        await route.fulfill({ contentType: 'application/json', body: envelope(created) });
        return;
      }
      await route.fulfill({ contentType: 'application/json', body: envelope(conversationList) });
    });
    await page.route('**/api/agent/interview-sessions/interview-1/conversations/*', async route => {
      if (route.request().method() !== 'PATCH') return route.fallback();
      const body = route.request().postDataJSON() as { title: string };
      const agentSessionId = route.request().url().split('/').pop()!;
      conversationList = conversationList.map(item => item.agentSessionId === agentSessionId
        ? { ...item, title: body.title }
        : item);
      const updated = conversationList.find(item => item.agentSessionId === agentSessionId)!;
      await route.fulfill({ contentType: 'application/json', body: envelope(updated) });
    });
    await page.route('**/api/agent/interview-sessions/interview-1/conversation*', async route => {
      const url = new URL(route.request().url());
      if (url.pathname.endsWith('/conversations')) return route.fallback();
      const id = url.searchParams.get('agentSessionId') || 'thread-a';
      await route.fulfill({
        contentType: 'application/json',
        body: envelope(id === 'thread-b' ? threadB : threadA),
      });
    });
    await page.route('**/api/agent/interview-sessions/interview-1/messages/stream', async route => {
      const events = [
        ['agent-step', { type: 'step', step: 'context', status: 'completed' }],
        ['agent-plan', { type: 'plan', step: 'loop', status: 'completed', content: '1. 读取面试报告\n2. 核对 Skill/RAG\n3. 输出建议' }],
        ['agent-step', { type: 'step', step: 'loop', status: 'running' }],
        ['agent-loop', { type: 'loop', step: 'loop-1', status: 'completed', message: '已读取面试报告；识别到 1 个待关注项。' }],
        ['agent-step', { type: 'step', step: 'action', status: 'running' }],
        ['agent-delta', { type: 'delta', content: 'Loop 已完成，建议优先补充故障定位案例。' }],
        ['agent-step', { type: 'step', step: 'loop', status: 'completed' }],
        ['agent-done', { type: 'done' }],
      ];
      const body = events.map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join('');
      await route.fulfill({ contentType: 'text/event-stream', body });
    });
  });

  test('切换页面、刷新和多线程都保持会话隔离', async ({ page }) => {
    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    await expect(page.getByText('线程 A 的复盘内容')).toBeVisible();

    await page.getByText('第二轮训练', { exact: true }).click();
    await expect(page).toHaveURL(/agentSessionId=thread-b/);
    await expect(page.getByText('线程 B 的训练内容')).toBeVisible();
    await expect(page.getByText('线程 A 的复盘内容')).not.toBeVisible();

    page.once('dialog', dialog => dialog.accept('第二轮训练（已重命名）'));
    await page.getByLabel('重命名 第二轮训练').click();
    await expect(page.getByText('第二轮训练（已重命名）')).toBeVisible();

    await page.reload();
    await expect(page).toHaveURL(/agentSessionId=thread-b/);
    await expect(page.getByText('线程 B 的训练内容')).toBeVisible();
  });

  test('从其他页面返回 Agent 时恢复保存的面试报告和线程', async ({ page }) => {
    await page.addInitScript(() => {
      sessionStorage.setItem('agent-active-interview-session', 'interview-1');
      sessionStorage.setItem('agent-active-thread-session', 'thread-b');
    });

    await page.goto('/agent');
    await expect(page).toHaveURL(/sessionId=interview-1/);
    await expect(page).toHaveURL(/agentSessionId=thread-b/);
    await expect(page.getByText('线程 B 的训练内容')).toBeVisible();
    await expect(page.getByText('线程 A 的复盘内容')).not.toBeVisible();
  });

  test('执行任务时展示 Loop 事件并保留最终回答', async ({ page }) => {
    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    await expect(page.getByRole('button', { name: '开始执行' })).toBeEnabled();
    await page.getByRole('button', { name: '开始执行' }).click();
    await expect(page.getByText('Loop 已完成，建议优先补充故障定位案例。')).toBeVisible();
    await expect(page.getByText('执行观察：已读取面试报告；识别到 1 个待关注项。')).toBeVisible();
  });

  test('可以编辑和删除单条长期记忆', async ({ page }) => {
    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    await expect(page.getByRole('region', { name: 'Agent 最近活动' })).toContainText('运行完成');
    await expect(page.getByLabel('编辑记忆：Redis 缓存')).toBeVisible();
    await expect(page.getByText('过期', { exact: false })).toBeVisible();

    await page.getByLabel('编辑记忆：Redis 缓存').click();
    await page.getByLabel('编辑记忆内容').fill('Redis 缓存穿透与击穿');
    await page.getByLabel('编辑记忆适用方向').fill('java-backend');
    await page.getByRole('button', { name: '保存' }).click();
    await expect(page.getByLabel('删除记忆：Redis 缓存穿透与击穿')).toBeVisible();

    page.once('dialog', dialog => dialog.accept());
    await page.getByLabel('删除记忆：Redis 缓存穿透与击穿').click();
    await expect(page.getByText('可管理的记忆项')).not.toBeVisible();
  });
});
