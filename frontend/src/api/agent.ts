import { streamSse } from './stream.ts';
import request from './request.ts';
import type {
  AgentConversationResponse,
  AgentConversationSummary,
  AgentMessageRequest,
  AgentStreamEvent,
} from '../types/agent.ts';
import { getAgentMemoryKey } from '../utils/clientIdentity.ts';

function agentHeaders() {
  return { 'X-User-Id': getAgentMemoryKey() };
}

interface AgentStreamHandlers {
  onEvent: (event: AgentStreamEvent) => void;
  onComplete: () => void;
  onError: (error: Error) => void;
}

export const agentApi = {
  getConversation(sessionId: string, agentSessionId?: string | null): Promise<AgentConversationResponse> {
    const query = agentSessionId ? `?agentSessionId=${encodeURIComponent(agentSessionId)}` : '';
    return request.get<AgentConversationResponse>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversation${query}`,
      { headers: agentHeaders() },
    );
  },

  listConversations(sessionId: string): Promise<AgentConversationSummary[]> {
    return request.get<AgentConversationSummary[]>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations`,
      { headers: agentHeaders() },
    );
  },

  createConversation(sessionId: string, title?: string): Promise<AgentConversationSummary> {
    return request.post<AgentConversationSummary>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations`,
      title?.trim() ? { title: title.trim() } : undefined,
      { headers: agentHeaders() },
    );
  },

  cancelRun(sessionId: string, agentSessionId?: string | null): Promise<AgentConversationResponse> {
    const query = agentSessionId ? `?agentSessionId=${encodeURIComponent(agentSessionId)}` : '';
    return request.post<AgentConversationResponse>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/cancel${query}`,
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
