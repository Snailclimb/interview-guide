const ACTIVE_SESSION_STORAGE_KEY = 'agent-active-interview-session';

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
