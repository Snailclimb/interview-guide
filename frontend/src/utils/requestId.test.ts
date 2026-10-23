import assert from 'node:assert/strict';
import test from 'node:test';
import { createRequestId } from './requestId.ts';

test('生成符合后端校验规则的请求 ID', () => {
  const requestId = createRequestId();

  assert.match(requestId, /^[A-Za-z0-9_-]{8,64}$/);
});

test('连续生成的请求 ID 不重复', () => {
  assert.notEqual(createRequestId(), createRequestId());
});

test('randomUUID 不可用时使用 getRandomValues 兼容 HTTP 页面', () => {
  const originalCrypto = globalThis.crypto;
  Object.defineProperty(globalThis, 'crypto', {
    configurable: true,
    value: {
      getRandomValues(bytes: Uint8Array) {
        bytes.fill(7);
        return bytes;
      },
    },
  });

  try {
    assert.match(
      createRequestId(),
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
    );
  } finally {
    Object.defineProperty(globalThis, 'crypto', {
      configurable: true,
      value: originalCrypto,
    });
  }
});
