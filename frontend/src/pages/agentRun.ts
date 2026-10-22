import type { AgentGoal } from '../types/agent';

export interface AgentRunRequest {
  goal: AgentGoal;
  message: string;
  providerId?: string;
}

export interface AgentRunMessageIds {
  userMessageId: number;
  assistantMessageId: number;
}

export interface AgentRunMessage {
  id: number;
  role: 'assistant' | 'user';
  content: string;
}

/**
 * 重试同一请求前移除上一次失败的临时消息，避免 UI 重复展示，
 * 也避免把错误提示作为 assistant 上下文再次发送给模型。
 */
export function removeLastRunMessages(
  messages: AgentRunMessage[],
  request: AgentRunRequest,
  ids?: AgentRunMessageIds | null,
): AgentRunMessage[] {
  let remaining = ids
    ? messages.filter((message) => message.id !== ids.userMessageId && message.id !== ids.assistantMessageId)
    : [...messages];

  if (!ids) {
    const lastIndex = remaining.length - 1;
    const previousIndex = lastIndex - 1;
    if (
      lastIndex >= 1
      && remaining[lastIndex].role === 'assistant'
      && remaining[previousIndex].role === 'user'
      && remaining[previousIndex].content === request.message
    ) {
      remaining = remaining.slice(0, previousIndex);
    } else if (
      lastIndex >= 0
      && remaining[lastIndex].role === 'user'
      && remaining[lastIndex].content === request.message
    ) {
      remaining = remaining.slice(0, lastIndex);
    }
  }

  return remaining;
}
