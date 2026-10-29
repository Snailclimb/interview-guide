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
  runUsage?: AgentRunUsage | null;
  loopPlan?: string | null;
  loopNextStep?: number;
  loopCompletedStepIndices?: number[];
  loopTrace?: AgentLoopTraceItem[];
}

export interface AgentLoopTraceItem {
  iteration: number;
  action: string;
  status: string;
  observation: string;
  attempts: number;
  durationMillis: number;
  failureReason?: string | null;
}

export interface AgentRunUsage {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  modelCalls: number;
  durationMillis: number;
  cost?: number | string | null;
  currency?: string | null;
  provider?: string | null;
  model?: string | null;
}

export interface AgentUsageCostSummary {
  provider?: string | null;
  currency?: string | null;
  amount: number | string;
}

export interface AgentUsageSummary {
  runCount: number;
  completedRunCount: number;
  failedRunCount: number;
  cancelledRunCount: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  modelCalls: number;
  durationMillis: number;
  costs: AgentUsageCostSummary[];
}

export interface AgentUsageRun {
  runId: string;
  interviewSessionId: string;
  interviewTitle?: string | null;
  skillId?: string | null;
  agentSessionId: string;
  agentSessionTitle?: string | null;
  runStatus?: string | null;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  modelCalls: number;
  durationMillis: number;
  cost?: number | string | null;
  currency?: string | null;
  provider?: string | null;
  model?: string | null;
  runStartedAt?: string | null;
  runFinishedAt?: string | null;
  createdAt: string;
}

export interface AgentAuditEvent {
  interviewSessionId?: string | null;
  agentSessionId?: string | null;
  action: string;
  resourceType: string;
  resourceId?: string | null;
  result?: string | null;
  createdAt: string;
  interviewTitle?: string | null;
  agentSessionTitle?: string | null;
}

export type AgentActionStatus = 'PROPOSED' | 'CONFIRMED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface AgentAction {
  actionId: string;
  interviewSessionId: string;
  agentSessionId: string;
  toolId: string;
  riskLevel: string;
  status: AgentActionStatus | string;
  title: string;
  previewText: string;
  draftContent: string;
  resultSummary?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  confirmedAt?: string | null;
  completedAt?: string | null;
}

export type AgentTrainingTaskStatus = 'TODO' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED';

export interface AgentTrainingTask {
  taskId: string;
  sourceInterviewSessionId: string;
  sourceAgentSessionId: string;
  sourceActionId: string;
  title: string;
  description: string;
  status: AgentTrainingTaskStatus | string;
  createdAt?: string | null;
  updatedAt?: string | null;
  completedAt?: string | null;
}

export function isAgentMemoryAction(action: AgentAction): boolean {
  return action.toolId === 'agent.memory.save';
}

export function isAgentTitleAction(action: AgentAction): boolean {
  return action.toolId === 'interview.title.rename';
}

export function isAgentReportExportAction(action: AgentAction): boolean {
  return action.toolId === 'interview.report.export';
}

export function isAgentResumeEditAction(action: AgentAction): boolean {
  return action.toolId === 'resume.editor.save';
}

export function isAgentScheduleAction(action: AgentAction): boolean {
  return action.toolId === 'interview.schedule.create';
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
  items: AgentMemoryItem[];
}

export interface AgentMemoryItem {
  id: string;
  category: 'weakness' | 'goal' | string;
  content: string;
  source?: string | null;
  jobScope?: string | null;
  confidence?: number | null;
  updatedAt?: string | null;
  expiresAt?: string | null;
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
  iteration?: number;
  attempts?: number;
  durationMillis?: number;
  failureReason?: string | null;
}
