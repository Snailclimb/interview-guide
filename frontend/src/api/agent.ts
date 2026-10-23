import { streamSse } from './stream.ts';
import request from './request.ts';
import type { AgentMessageRequest, AgentStreamEvent } from '../types/agent';
import type { AgentConversationResponse } from '../types/agent';
import { getAgentMemoryKey } from '../utils/clientIdentity';

function agentHeaders() {
  return { 'X-User-Id': getAgentMemoryKey() };
}

interface AgentStreamHandlers {
  onEvent: (event: AgentStreamEvent) => void;
  onComplete: () => void;
  onError: (error: Error) => void;
}

export const agentApi = {
  getConversation(sessionId: string): Promise<AgentConversationResponse> {
    return request.get<AgentConversationResponse>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversation`,
      { headers: agentHeaders() },
    );
  },

  cancelRun(sessionId: string): Promise<AgentConversationResponse> {
    return request.post<AgentConversationResponse>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/cancel`,
      undefined,
      { headers: agentHeaders() },
    );
  },

  clearMemory(): Promise<void> {
    return request.delete<void>('/api/agent/memory', { headers: agentHeaders() });
  },

  streamMessage(
    sessionId: string,
    request: AgentMessageRequest,
    handlers: AgentStreamHandlers,
    signal?: AbortSignal,
  ): Promise<void> {
    return streamSse({
      url: `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/messages/stream`,
      init: {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          ...agentHeaders(),
        },
        body: JSON.stringify({ ...request, memoryKey: request.memoryKey ?? getAgentMemoryKey() }),
        signal,
      },
      parseMode: 'event',
      // Agent 事件载荷是 JSON，去掉 SSE data: 后的协议分隔空格，避免回调拿到多余前缀。
      trimDataPrefixSpace: true,
      dataJoiner: '',
      preserveBusinessErrorEvents: true,
      onMessage: (chunk) => {
        try {
          handlers.onEvent(JSON.parse(chunk) as AgentStreamEvent);
        } catch {
          // 解析失败必须中断当前流；否则后续 onComplete 可能把页面状态覆盖成成功。
          throw new Error('Agent 返回了无法识别的事件');
        }
      },
      onComplete: handlers.onComplete,
      onError: handlers.onError,
    });
  },
};
