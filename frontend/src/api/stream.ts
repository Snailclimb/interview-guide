import { API_BASE_URL, getErrorMessage, getResultError, parseResultPayload } from './request.ts';

type SseParseMode = 'line' | 'event';

interface StreamSseOptions {
  url: string;
  init: RequestInit;
  onMessage: (chunk: string) => void;
  onComplete: () => void;
  onError: (error: Error) => void;
  parseMode?: SseParseMode;
  trimDataPrefixSpace?: boolean;
  unescapeEscapedNewlines?: boolean;
  dataJoiner?: string;
  /** 某些业务 SSE 需要把 error 作为正常事件交给调用方处理。 */
  preserveBusinessErrorEvents?: boolean;
}

/** 保留 SSE 建连阶段的 HTTP 状态，供页面区分参数/限流错误和连接中断。 */
export class StreamHttpError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'StreamHttpError';
    this.status = status;
  }
}

function toApiUrl(url: string): string {
  if (/^https?:\/\//i.test(url)) {
    return url;
  }
  return `${API_BASE_URL}${url}`;
}

function readCookie(name: string): string | null {
  if (typeof document === 'undefined') return null;
  const prefix = `${encodeURIComponent(name)}=`;
  const entry = document.cookie.split('; ').find(value => value.startsWith(prefix));
  return entry ? decodeURIComponent(entry.slice(prefix.length)) : null;
}

function isJsonContentType(contentType: string): boolean {
  return contentType.toLowerCase().includes('json');
}

async function readResponsePayload(response: Response): Promise<unknown> {
  const contentType = response.headers.get('content-type') ?? '';
  if (isJsonContentType(contentType)) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  try {
    return await response.text();
  } catch {
    return null;
  }
}

async function createHttpError(response: Response): Promise<StreamHttpError> {
  const payload = await readResponsePayload(response);
  const result = await parseResultPayload(payload);
  if (result) {
    return new StreamHttpError(result.message || `请求失败 (${response.status})`, response.status);
  }

  if (typeof payload === 'string' && payload.trim()) {
    return new StreamHttpError(payload.trim(), response.status);
  }

  return new StreamHttpError(`请求失败 (${response.status})`, response.status);
}

async function assertStreamResponse(response: Response): Promise<void> {
  if (!response.ok) {
    throw await createHttpError(response);
  }

  const contentType = response.headers.get('content-type') ?? '';
  if (!isJsonContentType(contentType)) {
    return;
  }

  const payload = await readResponsePayload(response);
  const result = await parseResultPayload(payload);
  const resultError = getResultError(result);
  if (resultError) {
    throw resultError;
  }

  throw new Error('服务端未返回流式数据');
}

function parseJsonObject(text: string): unknown | null {
  const trimmed = text.trim();
  if (!trimmed.startsWith('{')) {
    return null;
  }

  try {
    return JSON.parse(trimmed) as unknown;
  } catch {
    return null;
  }
}

function getStringField(value: unknown, key: string): string | null {
  if (value === null || typeof value !== 'object' || !(key in value)) {
    return null;
  }

  const field = (value as Record<string, unknown>)[key];
  return typeof field === 'string' ? field : null;
}

function getBusinessEventError(value: unknown): Error | null {
  const resultError = getResultError(value);
  if (resultError) {
    return resultError;
  }

  const marker = getStringField(value, 'type')
    ?? getStringField(value, 'event')
    ?? getStringField(value, 'status');
  if (!marker || !['error', 'failed', 'failure'].includes(marker.toLowerCase())) {
    return null;
  }

  return new Error(
    getStringField(value, 'message')
      ?? getStringField(value, 'error')
      ?? '请求失败'
  );
}

function assertNoBusinessError(content: string): void {
  const parsed = parseJsonObject(content);
  if (!parsed) {
    return;
  }

  const error = getBusinessEventError(parsed);
  if (error) {
    throw error;
  }
}

function normalizeLine(line: string): string {
  return line.endsWith('\r') ? line.slice(0, -1) : line;
}

function readDataLine(line: string, trimDataPrefixSpace: boolean): string | null {
  if (!line.startsWith('data:')) {
    return null;
  }

  let content = line.substring(5);
  if (trimDataPrefixSpace && content.startsWith(' ')) {
    content = content.substring(1);
  }

  return content.length === 0 ? '\n' : content;
}

function emitContent(content: string, options: StreamSseOptions): void {
  if (!options.preserveBusinessErrorEvents) {
    assertNoBusinessError(content);
  }
  const output = options.unescapeEscapedNewlines
    ? content.replace(/\\n/g, '\n').replace(/\\r/g, '\r')
    : content;
  options.onMessage(output);
}

function processLine(line: string, options: StreamSseOptions): void {
  const content = readDataLine(normalizeLine(line), options.trimDataPrefixSpace ?? true);
  if (content !== null) {
    emitContent(content, options);
  }
}

function flushLineBuffer(buffer: string, done: boolean, options: StreamSseOptions): string {
  const lines = buffer.split('\n');
  const remaining = done ? '' : lines.pop() ?? '';

  for (const line of lines) {
    processLine(line, options);
  }

  if (done && remaining) {
    processLine(remaining, options);
  }

  return remaining;
}

function processEventBlock(block: string, options: StreamSseOptions): void {
  if (!block.trim()) {
    return;
  }

  const dataParts: string[] = [];
  let eventName: string | null = null;

  for (const rawLine of block.split('\n')) {
    const line = normalizeLine(rawLine);
    if (line.startsWith('event:')) {
      eventName = line.substring(6).trim();
      continue;
    }

    const content = readDataLine(line, options.trimDataPrefixSpace ?? false);
    if (content !== null) {
      dataParts.push(content);
    }
  }

  if (dataParts.length === 0) {
    return;
  }

  const content = dataParts.join(options.dataJoiner ?? '\n');
  if (eventName?.toLowerCase() === 'error') {
    const parsed = parseJsonObject(content);
    const businessError = parsed ? getBusinessEventError(parsed) : null;
    throw businessError ?? new Error(content.trim() || '请求失败');
  }

  emitContent(content, options);
}

function flushEventBuffer(buffer: string, done: boolean, options: StreamSseOptions): string {
  let remaining = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  let separatorIndex = remaining.indexOf('\n\n');

  while (separatorIndex !== -1) {
    const block = remaining.substring(0, separatorIndex);
    processEventBlock(block, options);
    remaining = remaining.substring(separatorIndex + 2);
    separatorIndex = remaining.indexOf('\n\n');
  }

  if (!done) {
    const singleLineIndex = remaining.indexOf('\n');
    if (singleLineIndex !== -1 && remaining.substring(0, singleLineIndex).startsWith('data:')) {
      processEventBlock(remaining.substring(0, singleLineIndex), options);
      return remaining.substring(singleLineIndex + 1);
    }
  }

  if (done && remaining.trim()) {
    processEventBlock(remaining, options);
    return '';
  }

  return remaining;
}

function flushBuffer(buffer: string, done: boolean, options: StreamSseOptions): string {
  return options.parseMode === 'event'
    ? flushEventBuffer(buffer, done, options)
    : flushLineBuffer(buffer, done, options);
}

async function readStream(response: Response, options: StreamSseOptions): Promise<void> {
  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error('无法获取响应流');
  }

  const decoder = new TextDecoder();
  let buffer = '';
  try {
    while (true) {
      const { done, value } = await reader.read();

      if (done) {
        buffer += decoder.decode();
        flushBuffer(buffer, true, options);
        return;
      }

      buffer += decoder.decode(value, { stream: true });
      buffer = flushBuffer(buffer, false, options);
    }
  } catch (error) {
    // 解析失败、业务错误或读取异常都要释放底层长连接；正常 done 不需要 cancel。
    try {
      await reader.cancel();
    } catch {
      // 原始读取错误已经是调用方需要处理的原因，忽略清理阶段的次生错误。
    }
    throw error;
  }
}

export async function streamSse(options: StreamSseOptions): Promise<void> {
  try {
    const headers = new Headers(options.init.headers);
    const csrfToken = readCookie('XSRF-TOKEN');
    if (csrfToken && !headers.has('X-XSRF-TOKEN')) {
      headers.set('X-XSRF-TOKEN', csrfToken);
    }
    const response = await fetch(toApiUrl(options.url), {
      ...options.init,
      credentials: options.init.credentials ?? 'include',
      headers,
    });
    await assertStreamResponse(response);
    await readStream(response, options);
    options.onComplete();
  } catch (error) {
    options.onError(error instanceof Error ? error : new Error(getErrorMessage(error)));
  }
}
