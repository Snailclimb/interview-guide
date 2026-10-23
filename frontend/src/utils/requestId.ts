/**
 * 生成文字面试创建请求的幂等 ID。
 *
 * crypto.randomUUID() 仅在安全上下文（通常是 HTTPS 或 localhost）中可用。
 * 面试系统也支持通过 HTTP/IP 访问，因此这里必须提供兼容降级，避免点击
 * “开始文字面试”时在导航前抛异常。
 */
export function createRequestId(): string {
  const cryptoApi = globalThis.crypto as (Crypto & { randomUUID?: () => string }) | undefined;

  if (typeof cryptoApi?.randomUUID === 'function') {
    return cryptoApi.randomUUID();
  }

  if (typeof cryptoApi?.getRandomValues === 'function') {
    const bytes = new Uint8Array(16);
    cryptoApi.getRandomValues(bytes);
    // RFC 4122 version 4 UUID bits.
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    return formatUuid(bytes);
  }

  // 极老浏览器或受限 WebView 的最后降级。此 ID 只用于请求幂等，不承载安全信息。
  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

function formatUuid(bytes: Uint8Array): string {
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
