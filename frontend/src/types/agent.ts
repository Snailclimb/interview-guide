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
  memoryKey?: string;
  agentSessionId?: string;
}

export interface AgentConversationResponse {
  interviewSessionId: string;
  agentSessionId?: string | null;
  title?: string | null;
  goal?: AgentGoal | string | null;
  providerId?: string | null;
  runId?: string | null;
  runStatus?: 'IDLE' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | string | null;
  runStartedAt?: string | null;
  runFinishedAt?: string | null;
  runError?: string | null;
  runStep?: string | null;
  runStepStatus?: AgentStepStatus | string | null;
  runNotice?: string | null;
  runPartialAnswer?: string | null;
  runSources?: AgentSource[];
  messages: AgentHistoryMessage[];
}

export interface AgentConversationSummary {
  agentSessionId: string;
  interviewSessionId: string;
  title?: string | null;
  runStatus?: 'IDLE' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface AgentMemoryResponse {
  lastSkillId?: string | null;
  weaknesses: string[];
  recentGoals: string[];
  updatedAt?: string | null;
}

export type AgentStepStatus = 'pending' | 'running' | 'waiting' | 'completed' | 'failed' | 'cancelled';

export interface AgentSource {
  kind: 'skill' | 'rag';
  title: string;
  score?: number | null;
  reference: string;
}

export interface AgentStreamEvent {
  type: 'step' | 'plan' | 'loop' | 'delta' | 'sources' | 'done' | 'error';
  step?: string;
  status?: AgentStepStatus;
  content?: string;
  message?: string;
  sources?: AgentSource[];
}
