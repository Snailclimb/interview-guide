import { streamSse } from './stream';
import request from './request';
import type { AgentMessageRequest, AgentStreamEvent } from '../types/agent';
import type { AgentConversationResponse } from '../types/agent';

interface AgentStreamHandlers {
  onEvent: (event: AgentStreamEvent) => void;
  onComplete: () => void;
  onError: (error: Error) => void;
}

export const agentApi = {
  getConversation(sessionId: string): Promise<AgentConversationResponse> {
    return request.get<AgentConversationResponse>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversation`,
    );
  },

  cancelRun(sessionId: string): Promise<AgentConversationResponse> {
    return request.post<AgentConversationResponse>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/cancel`,
    );
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
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify(request),
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
          handlers.onError(new Error('Agent 返回了无法识别的事件'));
        }
      },
      onComplete: handlers.onComplete,
      onError: handlers.onError,
    });
  },
};
