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
    loopPlan: '1. 读取面试报告\n2. 核对训练依据',
    loopNextStep: 2,
    loopCompletedStepIndices: [0, 1],
    loopTrace: [{
      iteration: 1,
      action: '读取面试报告',
      status: 'completed',
      observation: '已读取面试报告；识别到 1 个待关注项。',
      attempts: 1,
      durationMillis: 120,
      failureReason: null,
    }],
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
    let auditRequestCount = 0;

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
      auditRequestCount += 1;
      if (auditRequestCount === 1) {
        await route.fulfill({ status: 503, contentType: 'text/plain', body: 'temporary unavailable' });
        return;
      }
      await route.fulfill({ contentType: 'application/json', body: envelope([
        {
          interviewSessionId: 'interview-1', agentSessionId: 'thread-a',
          action: 'RUN_COMPLETED', resourceType: 'agent-run', resourceId: 'run-1',
          result: 'success', createdAt: '2026-10-24T09:06:00',
          interviewTitle: 'Java 后端高并发面试', agentSessionTitle: '第一轮复盘',
        },
      ]) });
    });
    await page.route('**/api/agent/usage/summary', async route => {
      await route.fulfill({ contentType: 'application/json', body: envelope({
        runCount: 3,
        completedRunCount: 2,
        failedRunCount: 1,
        cancelledRunCount: 0,
        promptTokens: 1200,
        completionTokens: 800,
        totalTokens: 2000,
        modelCalls: 4,
        durationMillis: 12500,
        costs: [{ provider: 'pixel', currency: 'USD', amount: '0.30' }],
      }) });
    });
    await page.route('**/api/agent/usage/runs*', async route => {
      await route.fulfill({ contentType: 'application/json', body: envelope([{
        runId: 'run-1',
        interviewSessionId: 'interview-1',
        interviewTitle: 'Java 后端高并发面试',
        skillId: 'java-backend',
        agentSessionId: 'thread-a',
        agentSessionTitle: '第一轮复盘',
        runStatus: 'COMPLETED',
        promptTokens: 1200,
        completionTokens: 800,
        totalTokens: 2000,
        modelCalls: 4,
        durationMillis: 12500,
        cost: '0.30',
        currency: 'USD',
        provider: 'pixel',
        model: 'gpt-5.6-luna',
        runStartedAt: '2026-10-24T09:05:00',
        runFinishedAt: '2026-10-24T09:06:00',
        createdAt: '2026-10-24T09:06:00',
      }]) });
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
    await page.route('**/api/agent/interview-sessions/interview-1/actions*', async route => {
      await route.fulfill({ contentType: 'application/json', body: envelope([]) });
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
    const trainingTaskRequests: string[] = [];
    page.on('request', request => {
      if (request.url().includes('/api/agent/training-tasks')) trainingTaskRequests.push(request.url());
    });
    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    await expect(page.getByText('线程 A 的复盘内容')).toBeVisible();
    await expect(page.getByLabel('Agent 动作草稿')).not.toBeVisible();
    expect(trainingTaskRequests).toHaveLength(0);
    await expect(page.getByRole('region', { name: 'Agent Loop 轨迹' })).toContainText('已读取面试报告');
    const managementOverview = page.getByRole('region', { name: 'Agent 管理入口' });
    const agentNavigation = page.getByRole('navigation', { name: 'Agent 页面导航' });
    await expect(managementOverview).toBeVisible();
    await expect(managementOverview.getByRole('link', { name: /记忆管理/ })).toHaveAttribute('href', /\/agent\/memory\?sessionId=interview-1&agentSessionId=thread-a/);
    await agentNavigation.getByRole('link', { name: '用量与活动', exact: true }).click();
    await expect(page.getByRole('region', { name: 'Agent 累计用量' })).toContainText('2,000');
    await expect(page.getByRole('region', { name: 'Agent 累计用量' })).toContainText('pixel · USD 0.30');
    await expect(page.getByRole('region', { name: 'Agent 运行明细' })).toContainText('Java 后端高并发面试');
    await expect(page.getByRole('region', { name: 'Agent 运行明细' })).toContainText('第一轮复盘');
    await agentNavigation.getByRole('link', { name: '对话工作台', exact: true }).click();
    expect(trainingTaskRequests).toHaveLength(0);

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

  test('Agent 管理页按职责拆分且切换时保留当前报告和线程', async ({ page }) => {
    const trainingTaskRequests: string[] = [];
    page.on('request', request => {
      if (request.url().includes('/api/agent/training-tasks')) trainingTaskRequests.push(request.url());
    });
    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    const agentNavigation = page.getByRole('navigation', { name: 'Agent 页面导航' });

    await agentNavigation.getByRole('link', { name: '记忆管理', exact: true }).click();
    await expect(page).toHaveURL(/\/agent\/memory\?sessionId=interview-1&agentSessionId=thread-a/);
    await expect(page.getByRole('heading', { name: '记忆管理' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Agent 长期记忆' })).toContainText('Redis 缓存');
    await expect(page.getByRole('region', { name: '本场记忆应用预览' })).toContainText('本场生效');
    await expect(page.getByRole('region', { name: '本场记忆应用预览' })).toContainText('java-backend');
    await expect(page.getByPlaceholder('问问你的面试教练...')).not.toBeVisible();

    await agentNavigation.getByRole('link', { name: '动作与训练', exact: true }).click();
    await expect(page).toHaveURL(/\/agent\/actions\?sessionId=interview-1&agentSessionId=thread-a/);
    await expect(page.getByRole('heading', { name: '动作与训练', exact: true })).toBeVisible();
    await expect(page.getByLabel('Agent 动作草稿')).toBeVisible();
    await expect.poll(() => trainingTaskRequests.length).toBeGreaterThan(0);
    await expect(agentNavigation.getByRole('link', { name: '对话工作台', exact: true })).toHaveAttribute('href', /\/agent\?sessionId=interview-1&agentSessionId=thread-a/);

    await agentNavigation.getByRole('link', { name: '用量与活动', exact: true }).click();
    await expect(page).toHaveURL(/\/agent\/usage\?sessionId=interview-1&agentSessionId=thread-a/);
    await expect(page.getByRole('heading', { name: '用量与活动' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Agent 累计用量' })).toContainText('2,000');
    await expect(page.getByRole('region', { name: 'Agent 最近活动' })).toContainText('运行完成');
    await expect(page.getByRole('region', { name: 'Agent 最近活动' })).toContainText('Java 后端高并发面试');
  });

  test('新建 Agent 对话后立即选中并显示在列表中', async ({ page }) => {
    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    await page.getByRole('button', { name: '新建 Agent 对话' }).click();

    await expect(page).toHaveURL(/agentSessionId=thread-new/);
    await expect(page.getByText('新 Agent 对话', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '新建 Agent 对话' })).toBeEnabled();
  });

  test('当前线程执行时仍可新建其他 Agent 对话', async ({ page }) => {
    await page.route('**/api/agent/interview-sessions/interview-1/conversation*', async route => {
      const url = new URL(route.request().url());
      if (url.pathname.endsWith('/conversations')) {
        await route.fallback();
        return;
      }
      const agentSessionId = url.searchParams.get('agentSessionId') || 'thread-a';
      const current = agentSessionId === 'thread-a'
        ? { ...conversation('thread-a', '第一轮复盘', '线程 A 正在执行'), runStatus: 'RUNNING', runId: 'run-a' }
        : conversation(agentSessionId, '新 Agent 对话', '');
      await route.fulfill({ contentType: 'application/json', body: envelope(current) });
    });

    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    const createButton = page.getByRole('button', { name: '新建 Agent 对话' });
    await expect(createButton).toBeEnabled();
    await createButton.click();

    await expect(page).toHaveURL(/agentSessionId=thread-new/);
    await expect(page.getByText('新 Agent 对话', { exact: true })).toBeVisible();
  });

  test('登录会话失效时不继续显示假登录态', async ({ page }) => {
    await page.route('**/api/agent/interview-sessions/interview-1/conversations', async route => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 401,
          contentType: 'application/json',
          body: JSON.stringify({ code: 401, message: '请先登录', data: null }),
        });
        return;
      }
      await route.fallback();
    });

    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    await page.getByRole('button', { name: '新建 Agent 对话' }).click();

    await expect(page).toHaveURL(/\/login\?redirect=/);
    await expect(page.getByRole('heading', { name: 'AI Interview' })).toBeVisible();
  });

  test('面试报告失效时自动恢复到当前环境的有效报告', async ({ page }) => {
    await page.route('**/api/interview/sessions/missing/details', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ code: 3001, message: '面试会话不存在', data: null }),
      });
    });

    await page.goto('/agent?sessionId=missing');

    const createButton = page.getByRole('button', { name: '新建 Agent 对话' });
    await expect(page).toHaveURL(/sessionId=interview-1/);
    await expect(createButton).toBeEnabled();
    await createButton.click();
    await expect(page).toHaveURL(/sessionId=interview-1.*agentSessionId=thread-new/);
  });

  test('面试报告失效且当前账号没有记录时回到面试中心', async ({ page }) => {
    await page.route('**/api/interview/sessions/missing/details', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ code: 3001, message: '面试会话不存在', data: null }),
      });
    });
    await page.route('**/api/interview/sessions', async route => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data: [] }) });
      } else {
        await route.continue();
      }
    });

    await page.goto('/agent?sessionId=missing');

    await expect(page).toHaveURL(/\/interview-hub$/);
  });

  test('快速重复点击新建只创建一个 Agent 对话', async ({ page }) => {
    let createRequests = 0;
    let createRequestBody: { idempotencyKey?: string } | null = null;
    page.on('request', request => {
      if (request.method() === 'POST'
        && request.url().endsWith('/api/agent/interview-sessions/interview-1/conversations')) {
        createRequests += 1;
        createRequestBody = request.postDataJSON() as { idempotencyKey?: string };
      }
    });

    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    const createButton = page.getByRole('button', { name: '新建 Agent 对话' });
    await Promise.all([createButton.click(), createButton.click()]);

    await expect(page).toHaveURL(/agentSessionId=thread-new/);
    expect(createRequests).toBe(1);
    expect(createRequestBody?.idempotencyKey).toBeTruthy();
  });

  test('首次打开报告不偷偷创建线程，开始执行时自动建立线程', async ({ page }) => {
    let createRequests = 0;
    await page.unroute('**/api/agent/interview-sessions/interview-1/conversations');
    await page.unroute('**/api/agent/interview-sessions/interview-1/conversation*');
    await page.route('**/api/agent/interview-sessions/interview-1/conversations', async route => {
      if (route.request().method() === 'POST') {
        createRequests += 1;
        const created = {
          agentSessionId: 'thread-auto', interviewSessionId: 'interview-1',
          title: '新 Agent 对话', runStatus: 'IDLE',
          createdAt: '2026-10-24T09:05:00', updatedAt: '2026-10-24T09:05:00',
        };
        await route.fulfill({ contentType: 'application/json', body: envelope(created) });
        return;
      }
      await route.fulfill({ contentType: 'application/json', body: envelope([]) });
    });
    await page.route('**/api/agent/interview-sessions/interview-1/conversation*', async route => {
      const url = new URL(route.request().url());
      if (url.pathname.endsWith('/conversations')) {
        await route.fallback();
        return;
      }
      const id = url.searchParams.get('agentSessionId');
      await route.fulfill({
        contentType: 'application/json',
        body: envelope(id === 'thread-auto'
          ? conversation('thread-auto', '新 Agent 对话', '自动建立线程后的回答')
          : {
            ...conversation('', '', ''), agentSessionId: null, title: null, messages: [],
          }),
      });
    });

    await page.goto('/agent?sessionId=interview-1');
    await expect(page.getByRole('button', { name: '新建 Agent 对话' })).toBeVisible();
    await expect(page.getByText('还没有 Agent 对话，点击上方按钮新建。')).toBeVisible();
    expect(createRequests).toBe(0);

    await page.getByRole('button', { name: '开始执行' }).click();
    await expect(page).toHaveURL(/agentSessionId=thread-auto/);
    await expect(page.getByText('自动建立线程后的回答')).toBeVisible();
    expect(createRequests).toBe(1);
  });

  test('新建请求超时后重试复用同一个幂等键', async ({ page }) => {
    let createRequests = 0;
    const idempotencyKeys: string[] = [];
    await page.unroute('**/api/agent/interview-sessions/interview-1/conversations');
    await page.route('**/api/agent/interview-sessions/interview-1/conversations', async route => {
      if (route.request().method() !== 'POST') return route.fallback();
      createRequests += 1;
      idempotencyKeys.push((route.request().postDataJSON() as { idempotencyKey: string }).idempotencyKey);
      if (createRequests === 1) {
        await route.fulfill({ status: 503, contentType: 'text/plain', body: 'temporary unavailable' });
        return;
      }
      const created = { agentSessionId: 'thread-recovered', interviewSessionId: 'interview-1', title: '新 Agent 对话', runStatus: 'IDLE', createdAt: '2026-10-24T09:05:00', updatedAt: '2026-10-24T09:05:00' };
      await route.fulfill({ contentType: 'application/json', body: envelope(created) });
    });

    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    const createButton = page.getByRole('button', { name: '新建 Agent 对话' });
    await createButton.click();
    await expect(page.locator('p.text-rose-500').filter({ hasText: /失败|不可用|请求/ })).toBeVisible();
    await expect(createButton).toBeEnabled();
    await createButton.click();

    await expect(page).toHaveURL(/agentSessionId=thread-recovered/);
    expect(createRequests).toBe(2);
    expect(idempotencyKeys[0]).toBeTruthy();
    expect(idempotencyKeys[0]).toBe(idempotencyKeys[1]);
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

  test('直接打开 Agent 时自动选中最近面试报告并显示新建入口', async ({ page }) => {
    await page.addInitScript(() => {
      sessionStorage.removeItem('agent-active-interview-session');
      sessionStorage.removeItem('agent-active-thread-session');
    });

    await page.goto('/agent');

    await expect(page).toHaveURL(/sessionId=interview-1/);
    await expect(page.getByText('线程 A 的复盘内容')).toBeVisible();
    await expect(page.getByRole('button', { name: '新建 Agent 对话' })).toBeEnabled();
  });

  test('执行任务时展示 Loop 事件并保留最终回答', async ({ page }) => {
    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    await expect(page.getByRole('button', { name: '开始执行' })).toBeEnabled();
    await page.getByRole('button', { name: '开始执行' }).click();
    await expect(page.getByText('Loop 已完成，建议优先补充故障定位案例。')).toBeVisible();
    await expect(page.getByRole('region', { name: 'Agent Loop 轨迹' })).toContainText('已读取面试报告；识别到 1 个待关注项。');
  });

  test('自然语言安排复习时自动打开日程草稿并等待确认', async ({ page }) => {
    await page.route('**/api/agent/interview-sessions/interview-1/conversations/thread-a/actions/interview-schedule-draft', async route => {
      const payload = route.request().postDataJSON() as { companyName: string; position: string; interviewTime: string };
      expect(payload.companyName).toBeTruthy();
      expect(payload.position).toBeTruthy();
      expect(payload.interviewTime).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
      await route.fulfill({
        contentType: 'application/json',
        body: envelope({
          actionId: 'schedule-action-1',
          interviewSessionId: 'interview-1',
          agentSessionId: 'thread-a',
          toolId: 'interview.schedule.create',
          riskLevel: 'BUSINESS_WRITE',
          status: 'PROPOSED',
          title: '安排复习日程',
          previewText: '确认后创建复习日程',
          draftContent: `主题：${payload.companyName}\n岗位：${payload.position}\n时间：${payload.interviewTime}`,
          resultSummary: null,
        }),
      });
    });

    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    const input = page.getByPlaceholder('问问你的面试教练...');
    await input.fill('帮我安排明天下午3点复习 Redis');
    await input.press('Enter');

    await expect(page.getByLabel('复习日程草稿表单')).toBeVisible();
    await expect(page.getByLabel('复习时间')).toHaveValue(/T15:00$/);
    await page.getByRole('button', { name: '生成待确认日程' }).click();
    await expect(page.getByLabel('Agent 动作提示')).toContainText('复习日程草稿已生成');
    await page.route('**/api/agent/interview-sessions/interview-1/actions*', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: envelope([{
          actionId: 'schedule-action-1',
          interviewSessionId: 'interview-1',
          agentSessionId: 'thread-a',
          toolId: 'interview.schedule.create',
          riskLevel: 'BUSINESS_WRITE',
          status: 'PROPOSED',
          title: '安排复习日程',
          previewText: '确认后创建复习日程',
          draftContent: '主题：Java 后端高并发面试',
          resultSummary: null,
        }]),
      });
    });
    await page.getByLabel('Agent 动作提示').getByRole('link', { name: '去处理动作' }).click();
    const scheduleAction = page.getByLabel('Agent 动作草稿');
    await expect(scheduleAction.getByText('安排复习日程', { exact: true })).toBeVisible();
    await expect(scheduleAction.getByText('待确认', { exact: true })).toBeVisible();
  });

  test('自然语言创建训练任务时生成待确认任务草稿', async ({ page }) => {
    await page.route('**/api/agent/interview-sessions/interview-1/conversations/thread-a/actions/training-task-draft', async route => {
      const payload = route.request().postDataJSON() as { content: string; idempotencyKey: string };
      expect(payload.content).toContain('线程 A 的复盘内容');
      expect(payload.idempotencyKey).toMatch(/^auto:intent:trainingTask:thread-a:[0-9a-f]+$/);
      await route.fulfill({
        contentType: 'application/json',
        body: envelope({
          actionId: 'training-task-action-1',
          interviewSessionId: 'interview-1',
          agentSessionId: 'thread-a',
          toolId: 'training.task.create',
          riskLevel: 'BUSINESS_WRITE',
          status: 'PROPOSED',
          title: '创建训练任务',
          previewText: '确认后加入训练任务列表',
          draftContent: '重点练习 Redis 缓存穿透与击穿',
          resultSummary: null,
        }),
      });
    });

    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    const input = page.getByPlaceholder('问问你的面试教练...');
    await input.fill('帮我创建一个训练任务，重点练 Redis');
    await input.press('Enter');

    await expect(page.getByLabel('Agent 动作提示')).toContainText('训练任务草稿已生成');
    await page.route('**/api/agent/interview-sessions/interview-1/actions*', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: envelope([{
          actionId: 'training-task-action-1',
          interviewSessionId: 'interview-1',
          agentSessionId: 'thread-a',
          toolId: 'training.task.create',
          riskLevel: 'BUSINESS_WRITE',
          status: 'PROPOSED',
          title: '创建训练任务',
          previewText: '确认后加入训练任务列表',
          draftContent: '重点练习 Redis 缓存穿透与击穿',
          resultSummary: null,
        }]),
      });
    });
    await page.getByLabel('Agent 动作提示').getByRole('link', { name: '去处理动作' }).click();
    const taskAction = page.getByLabel('Agent 动作草稿');
    await expect(taskAction.getByText('创建训练任务', { exact: true })).toBeVisible();
    await expect(taskAction.getByText('待确认', { exact: true })).toBeVisible();
  });

  test('快捷动作可以基于最近回答生成待确认训练任务', async ({ page }) => {
    await page.route('**/api/agent/interview-sessions/interview-1/conversations/thread-a/actions/training-task-draft', async route => {
      const payload = route.request().postDataJSON() as { content: string; idempotencyKey: string };
      expect(payload.content).toBe('线程 A 的复盘内容');
      expect(payload.idempotencyKey).toMatch(/^task:thread-a:\d+$/);
      await route.fulfill({
        contentType: 'application/json',
        body: envelope({
          actionId: 'quick-task-action-1',
          interviewSessionId: 'interview-1',
          agentSessionId: 'thread-a',
          toolId: 'training.task.create',
          riskLevel: 'BUSINESS_WRITE',
          status: 'PROPOSED',
          title: '创建训练任务',
          previewText: '确认后加入训练任务列表',
          draftContent: '重点练习 Redis 缓存穿透与击穿',
          resultSummary: null,
        }),
      });
    });

    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    const quickActions = page.getByLabel('Agent 快捷动作');
    await quickActions.getByRole('button', { name: '创建训练任务' }).click();

    await expect(page.getByLabel('Agent 动作提示')).toContainText('训练任务草稿已生成');
    await page.route('**/api/agent/interview-sessions/interview-1/actions*', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: envelope([{
          actionId: 'quick-task-action-1',
          interviewSessionId: 'interview-1',
          agentSessionId: 'thread-a',
          toolId: 'training.task.create',
          riskLevel: 'BUSINESS_WRITE',
          status: 'PROPOSED',
          title: '创建训练任务',
          previewText: '确认后加入训练任务列表',
          draftContent: '重点练习 Redis 缓存穿透与击穿',
          resultSummary: null,
        }]),
      });
    });
    await page.getByLabel('Agent 动作提示').getByRole('link', { name: '去处理动作' }).click();
    const actionDraft = page.getByLabel('Agent 动作草稿');
    await expect(actionDraft.getByText('创建训练任务', { exact: true })).toBeVisible();
    await expect(actionDraft.getByText('待确认', { exact: true })).toBeVisible();
  });

  test('已创建的训练任务可以进入专项面试并自动推进状态', async ({ page }) => {
    const task = {
      taskId: 'training-task-1',
      sourceInterviewSessionId: 'interview-1',
      sourceAgentSessionId: 'thread-a',
      sourceActionId: 'training-task-action-1',
      title: '补 Redis 缓存',
      description: '完成 Redis 缓存穿透与击穿专项练习',
      status: 'TODO',
      createdAt: '2026-10-24T09:06:00',
      updatedAt: '2026-10-24T09:06:00',
      completedAt: null,
    };
    let updatedTask = task;
    let taskUpdatePayload: Record<string, unknown> | null = null;
    await page.route(/\/api\/agent\/training-tasks(?:\/.*)?(?:\?.*)?$/, async route => {
      if (route.request().method() === 'PATCH') {
        taskUpdatePayload = route.request().postDataJSON() as Record<string, unknown>;
        updatedTask = { ...task, status: 'IN_PROGRESS', updatedAt: '2026-10-24T09:07:00' };
        await route.fulfill({ contentType: 'application/json', body: envelope(updatedTask) });
        return;
      }
      await route.fulfill({ contentType: 'application/json', body: envelope([updatedTask]) });
    });

    let createPayload: Record<string, unknown> | null = null;
    await page.unroute('**/api/interview/sessions');
    await page.route('**/api/interview/sessions', async route => {
      if (route.request().method() !== 'POST') {
        await route.fulfill({ contentType: 'application/json', body: envelope(sessions) });
        return;
      }
      createPayload = route.request().postDataJSON() as Record<string, unknown>;
      await route.fulfill({
        contentType: 'application/json',
        body: envelope({
          sessionId: 'focused-interview-task-1',
          resumeText: '',
          totalQuestions: 3,
          currentQuestionIndex: 0,
          questions: [{
            questionIndex: 0,
            question: 'Redis 缓存穿透如何处理？',
            type: 'JAVA',
            category: 'Redis',
            userAnswer: null,
            score: null,
            feedback: null,
          }],
          status: 'CREATED',
        }),
      });
    });

    await page.goto('/agent/actions?sessionId=interview-1&agentSessionId=thread-a');
    const tasks = page.getByLabel('Agent 训练任务');
    await expect(tasks.getByText('补 Redis 缓存', { exact: true })).toBeVisible();
    const startTaskButton = tasks.getByRole('button', { name: '开始专项面试' });
    await expect(startTaskButton).toBeEnabled();
    await startTaskButton.click();
    await expect.poll(() => taskUpdatePayload).toMatchObject({ status: 'IN_PROGRESS' });
    await expect.poll(() => createPayload).toMatchObject({
      agentActionId: 'training-task-action-1',
      skillId: 'java-backend',
      forceCreate: true,
    });
    await expect(page).toHaveURL(/\/interview\/session\/focused-interview-task-1$/);
  });

  test('确认的练习草稿可以带着 Agent 动作进入专项文字面试', async ({ page }) => {
    const actionId = 'practice-action-confirmed';
    await page.unroute('**/api/agent/interview-sessions/interview-1/actions*');
    await page.route('**/api/agent/interview-sessions/interview-1/actions*', async route => {
      await route.fulfill({
        contentType: 'application/json',
        body: envelope([{
          actionId,
          interviewSessionId: 'interview-1',
          agentSessionId: 'thread-a',
          toolId: 'practice.questions.draft',
          riskLevel: 'DRAFT_WRITE',
          status: 'SUCCEEDED',
          title: '练习题草稿',
          previewText: '已确认的练习重点',
          draftContent: '重点练习 Redis 缓存穿透与击穿',
          resultSummary: '练习题草稿已保存',
          createdAt: '2026-10-24T09:06:00',
          updatedAt: '2026-10-24T09:06:00',
        }]),
      });
    });

    let createPayload: Record<string, unknown> | null = null;
    await page.unroute('**/api/interview/sessions');
    await page.route('**/api/interview/sessions', async route => {
      if (route.request().method() !== 'POST') {
        await route.fulfill({ contentType: 'application/json', body: envelope(sessions) });
        return;
      }
      createPayload = route.request().postDataJSON() as Record<string, unknown>;
      await route.fulfill({
        contentType: 'application/json',
        body: envelope({
          sessionId: 'focused-interview-1',
          resumeText: '',
          totalQuestions: 3,
          currentQuestionIndex: 0,
          questions: [{
            questionIndex: 0,
            question: 'Redis 缓存穿透如何处理？',
            type: 'JAVA',
            category: 'Redis',
            userAnswer: null,
            score: null,
            feedback: null,
          }],
          status: 'CREATED',
        }),
      });
    });

    await page.goto('/agent/actions?sessionId=interview-1&agentSessionId=thread-a');
    await page.getByRole('button', { name: '开始专项练习' }).click();
    await expect.poll(() => createPayload).not.toBeNull();
    expect(createPayload).toMatchObject({
      agentActionId: actionId,
      skillId: 'java-backend',
      forceCreate: true,
    });
    await expect(page).toHaveURL(/\/interview\/session\/focused-interview-1$/);
  });

  test('SSE 失败后保留原请求，重试成功且不重复显示失败消息', async ({ page }) => {
    await page.unroute('**/api/agent/interview-sessions/interview-1/messages/stream');
    let attempts = 0;
    await page.route('**/api/agent/interview-sessions/interview-1/messages/stream', async route => {
      attempts += 1;
      if (attempts === 1) {
        const body = `event: agent-error\ndata: ${JSON.stringify({
          type: 'error',
          message: '模型服务暂时不可用，请稍后重试',
        })}\n\n`;
        await route.fulfill({ contentType: 'text/event-stream', body });
        return;
      }
      const body = [
        ['agent-step', { type: 'step', step: 'context', status: 'completed' }],
        ['agent-delta', { type: 'delta', content: '重试后已完成复盘。' }],
        ['agent-done', { type: 'done' }],
      ].map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join('');
      await route.fulfill({ contentType: 'text/event-stream', body });
    });

    await page.goto('/agent?sessionId=interview-1&agentSessionId=thread-a');
    await page.getByRole('button', { name: '开始执行' }).click();
    await expect(page.getByText('模型服务暂时不可用，请稍后重试').first()).toBeVisible();
    await expect(page.getByRole('button', { name: '重试执行' })).toBeEnabled();

    await page.getByRole('button', { name: '重试执行' }).click();
    await expect(page.getByText('重试后已完成复盘。')).toBeVisible();
    await expect(page.getByText('模型服务暂时不可用，请稍后重试')).not.toBeVisible();
    await expect(page.getByText('本次任务未完成', { exact: false })).not.toBeVisible();
    expect(attempts).toBe(2);
  });

  test('可以编辑和删除单条长期记忆', async ({ page }) => {
    await page.goto('/agent/memory?sessionId=interview-1&agentSessionId=thread-a');
    await expect(page.getByLabel('编辑记忆：Redis 缓存')).toBeVisible();
    await expect(page.getByText('有效至', { exact: false })).toBeVisible();

    await page.getByLabel('编辑记忆：Redis 缓存').click();
    await page.getByLabel('编辑记忆内容').fill('Redis 缓存穿透与击穿');
    await page.getByLabel('编辑记忆适用方向').fill('java-backend');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByLabel('删除记忆：Redis 缓存穿透与击穿')).toBeVisible();

    page.once('dialog', dialog => dialog.accept());
    await page.getByLabel('删除记忆：Redis 缓存穿透与击穿').click();
    await expect(page.getByText('可管理的记忆项')).not.toBeVisible();
  });

  test('记忆读取遇到临时故障会自动重试，不会误显示为空', async ({ page }) => {
    let memoryRequests = 0;
    await page.route('**/api/agent/memory', async route => {
      memoryRequests += 1;
      if (memoryRequests < 4) {
        await route.fulfill({ status: 503, contentType: 'text/plain', body: 'temporary unavailable' });
        return;
      }
      await route.fulfill({ contentType: 'application/json', body: envelope({
        lastSkillId: 'java-backend',
        weaknesses: ['Redis 缓存'],
        recentGoals: ['practice'],
        updatedAt: '2026-10-24T09:05:00',
        items: [{
          id: 'memory-retry', category: 'weakness', content: 'Redis 缓存', source: 'interview-report',
          jobScope: 'java-backend', confidence: 0.9, updatedAt: '2026-10-24T09:05:00', expiresAt: null,
        }],
      }) });
    });

    await page.goto('/agent/memory?sessionId=interview-1&agentSessionId=thread-a');
    await expect(page.getByRole('region', { name: 'Agent 长期记忆' })).toContainText('Redis 缓存');
    await expect(page.getByText('暂无长期记忆，完成一次 Agent 复盘后会自动积累。')).not.toBeVisible();
    expect(memoryRequests).toBeGreaterThanOrEqual(4);
  });

  test('没有 Agent 线程时不提前请求完整动作列表', async ({ page }) => {
    let actionRequests = 0;
    await page.route('**/api/agent/interview-sessions/interview-1/conversations', async route => {
      await route.fulfill({ contentType: 'application/json', body: envelope([]) });
    });
    const fulfillEmptyConversation = async (route: import('@playwright/test').Route) => {
      await route.fulfill({ contentType: 'application/json', body: envelope({
        interviewSessionId: 'interview-1', agentSessionId: null, messages: [], runStatus: 'IDLE',
      }) });
    };
    await page.route('**/api/agent/interview-sessions/interview-1/conversation', fulfillEmptyConversation);
    await page.route('**/api/agent/interview-sessions/interview-1/conversation?*', fulfillEmptyConversation);
    await page.route('**/api/agent/interview-sessions/interview-1/actions*', async route => {
      actionRequests += 1;
      await route.fulfill({ contentType: 'application/json', body: envelope([]) });
    });

    await page.goto('/agent?sessionId=interview-1');
    await expect(page.getByText('还没有 Agent 对话，点击上方按钮新建。')).toBeVisible();
    expect(actionRequests).toBe(0);
  });

  test('用量与活动可以按面试和 Agent 会话筛选', async ({ page }) => {
    await page.unroute('**/api/agent/usage/runs*');
    await page.route('**/api/agent/usage/runs*', async route => {
      await route.fulfill({ contentType: 'application/json', body: envelope([
        {
          runId: 'run-a', interviewSessionId: 'interview-1', interviewTitle: 'Java 后端高并发面试',
          skillId: 'java-backend', agentSessionId: 'thread-a', agentSessionTitle: '第一轮复盘',
          runStatus: 'COMPLETED', promptTokens: 100, completionTokens: 50, totalTokens: 150,
          modelCalls: 1, durationMillis: 1000, cost: null, currency: null, provider: 'pixel',
          model: 'gpt-5.6-luna', runStartedAt: '2026-10-24T09:05:00', runFinishedAt: '2026-10-24T09:06:00',
          createdAt: '2026-10-24T09:05:00',
        },
        {
          runId: 'run-b', interviewSessionId: 'interview-1', interviewTitle: 'Java 后端高并发面试',
          skillId: 'java-backend', agentSessionId: 'thread-b', agentSessionTitle: '第二轮训练',
          runStatus: 'FAILED', promptTokens: 80, completionTokens: 0, totalTokens: 80,
          modelCalls: 1, durationMillis: 800, cost: null, currency: null, provider: 'pixel',
          model: 'gpt-5.6-luna', runStartedAt: '2026-10-24T09:07:00', runFinishedAt: '2026-10-24T09:08:00',
          createdAt: '2026-10-24T09:07:00',
        },
      ]) });
    });
    await page.unroute('**/api/agent/audit/events*');
    await page.route('**/api/agent/audit/events*', async route => {
      await route.fulfill({ contentType: 'application/json', body: envelope([
        {
          interviewSessionId: 'interview-1', agentSessionId: 'thread-a', action: 'RUN_COMPLETED',
          resourceType: 'agent-run', resourceId: 'run-a', result: 'success', createdAt: '2026-10-24T09:06:00',
          interviewTitle: 'Java 后端高并发面试', agentSessionTitle: '第一轮复盘',
        },
        {
          interviewSessionId: 'interview-1', agentSessionId: 'thread-b', action: 'RUN_FAILED',
          resourceType: 'agent-run', resourceId: 'run-b', result: 'failed', createdAt: '2026-10-24T09:08:00',
          interviewTitle: 'Java 后端高并发面试', agentSessionTitle: '第二轮训练',
        },
      ]) });
    });

    await page.goto('/agent/usage');
    const filter = page.getByLabel('筛选 Agent 来源会话');
    await expect(filter.locator('option')).toHaveCount(3);
    await filter.selectOption('interview-1::thread-b');
    await expect(filter).toHaveValue('interview-1::thread-b');
    await expect(page.getByRole('region', { name: 'Agent 运行明细' }).getByText('Agent 会话：第二轮训练')).toBeVisible();
    await expect(page.getByRole('region', { name: 'Agent 运行明细' }).getByText('Agent 会话：第一轮复盘')).not.toBeVisible();
    await expect(page.getByText('显示 1 / 2 条运行记录')).toBeVisible();
  });
});
