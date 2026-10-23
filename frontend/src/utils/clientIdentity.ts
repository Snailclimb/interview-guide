const STORAGE_KEY = 'interview-agent-memory-key';

/** 为没有登录体系的本地部署提供稳定的浏览器级长期记忆键。 */
export function getAgentMemoryKey(): string {
  if (typeof window === 'undefined') return 'anonymous';
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    if (saved) return saved;
    const cryptoApi = window.crypto;
    const value = cryptoApi?.getRandomValues
      ? Array.from(cryptoApi.getRandomValues(new Uint8Array(16)))
        .map((item) => item.toString(16).padStart(2, '0')).join('')
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    window.localStorage.setItem(STORAGE_KEY, value);
    return value;
  } catch {
    return `anonymous-${Date.now()}`;
  }
}
