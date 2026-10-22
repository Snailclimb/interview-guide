export type AgentGoal = 'review' | 'practice' | 'plan';

export interface AgentHistoryMessage {
  role: 'assistant' | 'user';
  content: string;
}

export interface AgentMessageRequest {
  goal: AgentGoal;
  message: string;
  history: AgentHistoryMessage[];
  providerId?: string;
}

export interface AgentConversationResponse {
  interviewSessionId: string;
  goal?: AgentGoal | string | null;
  providerId?: string | null;
  runId?: string | null;
  runStatus?: 'IDLE' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | string | null;
  runStartedAt?: string | null;
  runFinishedAt?: string | null;
  runError?: string | null;
  messages: AgentHistoryMessage[];
}

export type AgentStepStatus = 'pending' | 'running' | 'waiting' | 'completed';

export interface AgentSource {
  kind: 'skill' | 'rag';
  title: string;
  score?: number | null;
  reference: string;
}

export interface AgentStreamEvent {
  type: 'step' | 'delta' | 'sources' | 'done' | 'error';
  step?: string;
  status?: AgentStepStatus;
  content?: string;
  message?: string;
  sources?: AgentSource[];
}
