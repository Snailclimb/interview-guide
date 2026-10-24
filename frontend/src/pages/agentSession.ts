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
