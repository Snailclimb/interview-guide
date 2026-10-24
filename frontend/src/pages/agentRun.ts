import type { AgentConversationResponse, AgentGoal } from '../types/agent';

export interface AgentRunRequest {
  goal: AgentGoal;
  message: string;
  providerId?: string;
  agentSessionId?: string;
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

/** 重试时优先使用用户当前选择的模型；没有选择时才沿用失败任务的模型。 */
export function resolveRetryProviderId(currentProviderId: string, previousProviderId?: string) {
  const current = currentProviderId.trim();
  return current || previousProviderId;
}

function isAgentGoal(value: string | null | undefined): value is AgentGoal {
  return value === 'review' || value === 'practice' || value === 'plan';
}

/** 从持久化失败会话中恢复可重试的原始用户请求。 */
export function getRetryableRequest(conversation: AgentConversationResponse): AgentRunRequest | null {
  if (conversation.runStatus !== 'FAILED') return null;
  const lastUserMessage = [...conversation.messages].reverse()
    .find((message) => message.role === 'user' && message.content.trim().length > 0);
  if (!lastUserMessage) return null;

  return {
    goal: isAgentGoal(conversation.goal) ? conversation.goal : 'review',
    message: lastUserMessage.content,
    providerId: conversation.providerId || undefined,
    agentSessionId: conversation.agentSessionId || undefined,
  };
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
