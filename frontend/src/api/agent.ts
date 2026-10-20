import { streamSse } from './stream';
import type { AgentMessageRequest, AgentStreamEvent } from '../types/agent';

interface AgentStreamHandlers {
  onEvent: (event: AgentStreamEvent) => void;
  onComplete: () => void;
  onError: (error: Error) => void;
}

export const agentApi = {
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
      trimDataPrefixSpace: false,
      dataJoiner: '',
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
