import assert from 'node:assert/strict';
import test from 'node:test';
import {downloadBlob} from './download.ts';

test('Blob 下载不会在点击后立即释放对象 URL', () => {
  const globalObject = globalThis as unknown as {window?: Window; document?: Document};
  const previousWindow = globalObject.window;
  const previousDocument = globalObject.document;
  let scheduledCleanup: (() => void) | undefined;
  let revoked = 0;
  let clicked = 0;
  let appended = 0;
  let removed = 0;
  const anchor = {
    href: '',
    download: '',
    style: {display: ''},
    click: () => { clicked += 1; },
    remove: () => { removed += 1; },
  };

  globalObject.window = {
    URL: {
      createObjectURL: () => 'blob:test',
      revokeObjectURL: () => { revoked += 1; },
    },
    setTimeout: (callback: TimerHandler) => {
      scheduledCleanup = callback as () => void;
      return 1;
    },
  } as unknown as Window;
  globalObject.document = {
    createElement: () => anchor,
    body: {appendChild: () => { appended += 1; }},
  } as unknown as Document;

  try {
    downloadBlob(new Blob(['resume']), '简历编辑版.pdf');

    assert.equal(clicked, 1);
    assert.equal(appended, 1);
    assert.equal(removed, 1);
    assert.equal(anchor.download, '简历编辑版.pdf');
    assert.equal(revoked, 0);

    scheduledCleanup?.();
    assert.equal(revoked, 1);
  } finally {
    globalObject.window = previousWindow;
    globalObject.document = previousDocument;
  }
});
