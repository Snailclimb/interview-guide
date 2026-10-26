const ACTIVE_SESSION_STORAGE_KEY = 'agent-active-interview-session';
const ACTIVE_AGENT_THREAD_STORAGE_KEY = 'agent-active-thread-session';

/** 页面切换时保留当前 Agent 绑定的面试报告；使用 sessionStorage 避免跨浏览器会话串用。 */
export function getStoredAgentSessionId(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.sessionStorage.getItem(ACTIVE_SESSION_STORAGE_KEY);
  } catch {
    return null;
  }
}

export function rememberAgentSessionId(sessionId: string): void {
  if (!sessionId || typeof window === 'undefined') return;
  try {
    window.sessionStorage.setItem(ACTIVE_SESSION_STORAGE_KEY, sessionId);
  } catch {
    // 存储被浏览器策略禁用时，当前 URL 仍然可以正常使用 Agent。
  }
}

export function resolveAgentSessionId(
  routeSessionId: string | null,
  storedSessionId: string | null,
): string | null {
  return routeSessionId || storedSessionId || null;
}

/**
 * 恢复 Agent 线程时，只有保存的面试报告仍然是当前报告，才能复用保存的线程。
 *
 * 页面从 /agent 离开后再回来时，路由通常先没有参数，随后才补回 sessionId；
 * 不能因为 sessionId 已经补回而误认为用户主动切换了报告，否则会丢失当前线程。
 */
export function resolveAgentThreadId(
  routeSessionId: string | null,
  routeAgentSessionId: string | null,
  storedSessionId: string | null,
  storedAgentSessionId: string | null,
): string | null {
  if (routeAgentSessionId) return routeAgentSessionId;
  if (!storedAgentSessionId) return null;
  if (!routeSessionId || routeSessionId === storedSessionId) return storedAgentSessionId;
  return null;
}

export function getStoredAgentThreadId(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.sessionStorage.getItem(ACTIVE_AGENT_THREAD_STORAGE_KEY);
  } catch {
    return null;
  }
}

export function rememberAgentThreadId(agentSessionId: string | null): void {
  if (typeof window === 'undefined') return;
  try {
    if (agentSessionId) window.sessionStorage.setItem(ACTIVE_AGENT_THREAD_STORAGE_KEY, agentSessionId);
    else window.sessionStorage.removeItem(ACTIVE_AGENT_THREAD_STORAGE_KEY);
  } catch {
    // 当前 URL 仍然是线程状态的最终来源。
  }
}
