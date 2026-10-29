import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  ArrowLeft,
  Activity,
  Bot,
  ClipboardCheck,
  Copy,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Circle,
  Clock3,
  Download,
  FileText,
  Loader2,
  MessageSquare,
  Pencil,
  Play,
  RotateCcw,
  Send,
  Settings2,
  Sparkles,
  Target,
  Trash2,
  UserRound,
  XCircle,
} from 'lucide-react';
import { agentApi } from '../api/agent';
import { historyApi, type InterviewDetail } from '../api/history';
import { interviewApi } from '../api/interview';
import { llmProviderApi } from '../api/llmProvider';
import { getErrorMessage } from '../api/request';
import { StreamHttpError } from '../api/stream';
import { downloadBlob } from '../utils/download';
import CodeBlock from '../components/CodeBlock';
import { getRetryableRequest, removeLastRunMessages, resolveRetryProviderId, type AgentRunMessageIds, type AgentRunRequest } from './agentRun';
import { normalizeAgentMarkdown } from './agentMarkdown';
import { isNearChatBottom } from './agentChatScroll';
import { detectAgentActionIntent, type AgentActionIntent } from './agentIntent';
import {
  getStoredAgentSessionId,
  getStoredAgentThreadId,
  clearStoredAgentSession,
  rememberAgentSessionId,
  rememberAgentThreadId,
  resolveAgentSessionId,
  resolveAgentThreadId,
} from './agentSession';
import { isAgentReportExportAction, isAgentResumeEditAction, isAgentScheduleAction } from '../types/agent';
import type {
  AgentGoal,
  AgentAuditEvent,
  AgentAction,
  AgentConversationResponse,
  AgentConversationSummary,
  AgentHistoryMessage,
  AgentMemoryItem,
  AgentMemoryResponse,
  AgentTrainingTask,
  AgentTrainingTaskStatus,
  AgentRunUsage,
  AgentUsageSummary,
  AgentUsageRun,
  AgentSource,
  AgentLoopTraceItem,
  AgentStreamEvent,
} from '../types/agent';
import type { ProviderItem } from '../types/llmProvider';
import AgentSessionList from '../components/AgentSessionList';
import AgentNavigation from '../components/AgentNavigation';
import AgentManagementOverview from '../components/AgentManagementOverview';
import { ROUTES } from '../constants/routes';
import { createRequestId } from '../utils/requestId';
type RunStatus = 'idle' | 'running' | 'completed' | 'error' | 'cancelled';
type StepStatus = 'pending' | 'running' | 'waiting' | 'completed' | 'failed' | 'cancelled';

const AUDIT_RETRY_DELAYS_MS = [500, 1000, 2000];

interface AgentStep {
  id: string;
  title: string;
  description: string;
  status: StepStatus;
}

interface AgentMessage {
  id: number;
  role: 'assistant' | 'user';
  content: string;
}

interface ScheduleDraftForm {
  messageId: number;
  companyName: string;
  position: string;
  interviewTime: string;
  notes: string;
}

const MAX_HISTORY_MESSAGES = 32;
const MAX_AGENT_MESSAGE_CHARS = 4000;
const MAX_RECONCILIATION_ATTEMPTS = 300;
const RECONCILIATION_INTERVAL_MS = 3000;

const goalOptions: Array<{ id: AgentGoal; title: string; description: string }> = [
  { id: 'review', title: '复盘本场面试', description: '分析表现，提炼优势和改进点' },
  { id: 'practice', title: '针对性训练', description: '围绕薄弱知识点生成练习题' },
  { id: 'plan', title: '制定训练计划', description: '拆解目标，安排下一步行动' },
];

const createWelcomeMessage = (): AgentMessage => ({
  id: 1,
  role: 'assistant',
  content: '你好，我是你的 AI 面试教练。选择一个目标后，我会基于当前面试上下文帮你整理下一步。',
});

const createInitialSteps = (): AgentStep[] => [
  { id: 'context', title: '整理面试上下文', description: '读取本场面试的问题、回答和评分', status: 'pending' },
  { id: 'planning', title: '规划处理路径', description: '确定是否需要 Skill 或知识库资料支持', status: 'pending' },
  { id: 'loop', title: '执行受控 Agent Loop', description: '按有限步骤调用工具并汇总可验证上下文', status: 'pending' },
  { id: 'insight', title: '识别表现模式', description: '归纳回答中的优势、风险和知识盲区', status: 'pending' },
  { id: 'retrieval', title: '检索相关知识', description: '结合 Skill 和知识库补充训练依据', status: 'pending' },
  { id: 'action', title: '生成下一步建议', description: '给出可执行的训练任务和追问方向', status: 'pending' },
];

function formatDate(value?: string | null) {
  if (!value) return '暂无时间';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function formatDurationMillis(value: number) {
  const totalSeconds = Math.max(0, Math.round(value / 1000));
  if (totalSeconds < 60) return `${totalSeconds} 秒`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return seconds ? `${minutes} 分 ${seconds} 秒` : `${minutes} 分`;
}

function formatRunStatus(status?: string | null) {
  const labels: Record<string, string> = {
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
  };
  return labels[status || ''] || status || '未知状态';
}

function runStatusClass(status?: string | null) {
  if (status === 'FAILED') return 'text-rose-600 dark:text-rose-300';
  if (status === 'CANCELLED') return 'text-slate-500 dark:text-slate-400';
  return 'text-emerald-600 dark:text-emerald-300';
}

function agentSessionHref(interviewSessionId?: string | null, agentSessionId?: string | null) {
  if (!interviewSessionId) return null;
  const params = new URLSearchParams({ sessionId: interviewSessionId });
  if (agentSessionId) params.set('agentSessionId', agentSessionId);
  return `${ROUTES.agent}?${params.toString()}`;
}

function formatAuditAction(action: string) {
  const labels: Record<string, string> = {
    RUN_STARTED: '开始运行',
    RUN_COMPLETED: '运行完成',
    RUN_FAILED: '运行失败',
    RUN_CANCELLED: '取消运行',
    MEMORY_REMEMBERED: '写入记忆',
    MEMORY_UPDATED: '更新记忆',
    MEMORY_DELETED: '删除记忆',
    MEMORY_CLEARED: '清除记忆',
    ACTION_PROPOSED: '提出动作',
    ACTION_CONFIRMED: '确认动作',
    ACTION_RUNNING: '执行动作',
    ACTION_RETRY: '重试动作',
    ACTION_SUCCEEDED: '动作完成',
    ACTION_DRAFT_UPDATED: '修改动作草稿',
    ACTION_CANCELLED: '取消动作',
    ACTION_FAILED: '动作失败',
    ACTION_RECOVERED: '恢复中断动作',
    TRAINING_TASK_CREATED: '创建训练任务',
    TRAINING_TASK_STATUS_UPDATED: '更新训练任务',
  };
  return labels[action] || action;
}

function formatAgentActionStatus(status: string) {
  const labels: Record<string, string> = {
    PROPOSED: '待确认',
    CONFIRMED: '已确认',
    RUNNING: '执行中',
    SUCCEEDED: '已保存',
    FAILED: '失败',
    CANCELLED: '已取消',
  };
  return labels[status] || status;
}

function agentActionResultClass(status: string) {
  if (status === 'FAILED') return 'text-rose-700 dark:text-rose-300';
  if (status === 'CANCELLED') return 'text-slate-500 dark:text-slate-400';
  return 'text-emerald-700 dark:text-emerald-300';
}

function compactMemoryDraft(content: string) {
  return content
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/^\s*#+\s*/gm, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 160);
}

function compactResumeEditDraft(content: string) {
  return content.replace(/\u0000/g, '').trim().slice(0, 12_000);
}

function defaultScheduleTime() {
  const next = new Date(Date.now() + 24 * 60 * 60 * 1000);
  next.setMinutes(0, 0, 0);
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${next.getFullYear()}-${pad(next.getMonth() + 1)}-${pad(next.getDate())}T${pad(next.getHours())}:${pad(next.getMinutes())}`;
}

function suggestInterviewTitle(content: string, fallback: string) {
  const heading = content
    .split('\n')
    .map((line) => line.replace(/^\s*#+\s*/, '').replace(/^[-*]\s*/, '').trim())
    .find((line) => line.length >= 4);
  const candidate = (heading || fallback).replace(/[*_`]/g, '').replace(/\s+/g, ' ').trim();
  return candidate.slice(0, 120);
}

function stableActionKey(kind: string, agentSessionId: string, content: string) {
  let hash = 2166136261;
  for (let index = 0; index < content.length; index += 1) {
    hash ^= content.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return `auto:${kind}:${agentSessionId}:${(hash >>> 0).toString(16)}`;
}

function getScoreTone(score: number | null | undefined) {
  if (score == null) return 'text-slate-400';
  if (score >= 80) return 'text-emerald-500';
  if (score >= 60) return 'text-amber-500';
  return 'text-rose-500';
}

function buildAgentHistory(messages: AgentMessage[]): AgentHistoryMessage[] {
  return messages
    .filter((message) => message.content.trim().length > 0)
    .slice(-MAX_HISTORY_MESSAGES)
    .map(({ role, content }) => ({ role, content }));
}

function AgentMarkdown({ content }: { content: string }) {
  return (
    <div className="agent-markdown prose prose-slate dark:prose-invert prose-sm max-w-none break-words [overflow-wrap:anywhere]">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          h1: ({ children }) => <h3 className="!mt-0 !mb-3 !text-lg !font-bold !leading-snug text-slate-900 dark:text-white">{children}</h3>,
          h2: ({ children }) => <h3 className="!mt-4 !mb-2 !text-base !font-bold !leading-snug text-slate-900 dark:text-white">{children}</h3>,
          h3: ({ children }) => <h4 className="!mt-4 !mb-2 !text-sm !font-bold !leading-snug text-primary-700 dark:text-primary-300">{children}</h4>,
          p: ({ children }) => <p className="!my-2 leading-7">{children}</p>,
          ul: ({ children }) => <ul className="!my-2 !pl-5 space-y-1">{children}</ul>,
          ol: ({ children }) => <ol className="!my-2 !pl-5 space-y-1">{children}</ol>,
          li: ({ children }) => <li className="leading-7 [&>p]:!my-0">{children}</li>,
          blockquote: ({ children }) => <blockquote className="!my-3 border-l-4 border-primary-300 dark:border-primary-600 bg-primary-50/70 dark:bg-primary-900/20 px-3 py-2 text-slate-600 dark:text-slate-300">{children}</blockquote>,
          hr: () => <hr className="!my-4 border-slate-200 dark:border-slate-600" />,
          code: ({ className, children }) => {
            const match = /language-(\w+)/.exec(className || '');
            if (!match) {
              return (
                <code className="bg-slate-200/80 dark:bg-slate-600 text-primary-600 dark:text-primary-300 px-1.5 py-0.5 rounded-md text-sm font-normal">
                  {children}
                </code>
              );
            }
            return (
              <CodeBlock language={match[1]}>
                {String(children).replace(/\n$/, '')}
              </CodeBlock>
            );
          },
          pre: ({ children }) => <>{children}</>,
        }}
      >
        {normalizeAgentMarkdown(content)}
      </ReactMarkdown>
    </div>
  );
}

export type AgentView = 'workspace' | 'memory' | 'actions' | 'usage';

interface AgentPageProps {
  view?: AgentView;
}

export default function AgentPage({ view = 'workspace' }: AgentPageProps) {
  const isWorkspaceView = view === 'workspace';
  const needsInterviewContext = isWorkspaceView || view === 'actions' || view === 'memory';
  const viewTitle = view === 'memory'
    ? '记忆管理'
    : view === 'actions'
      ? '动作与训练'
      : view === 'usage'
        ? '用量与活动'
        : 'AI 面试教练';
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const routeSessionId = searchParams.get('sessionId');
  const routeAgentSessionId = searchParams.get('agentSessionId');
  const storedSessionId = useMemo(() => getStoredAgentSessionId(), []);
  const storedAgentSessionId = useMemo(() => getStoredAgentThreadId(), []);
  const sessionId = resolveAgentSessionId(routeSessionId, storedSessionId);
  // 地址带有报告 ID 时不使用上一次报告的线程；无参数进入 /agent 时才从 sessionStorage 恢复。
  const agentSessionId = resolveAgentThreadId(
    routeSessionId,
    routeAgentSessionId,
    storedSessionId,
    storedAgentSessionId,
  );
  const [goal, setGoal] = useState<AgentGoal>('review');
  const [providerId, setProviderId] = useState('');
  const [providers, setProviders] = useState<ProviderItem[]>([]);
  const [interview, setInterview] = useState<InterviewDetail | null>(null);
  const [loadingContext, setLoadingContext] = useState(needsInterviewContext && Boolean(sessionId));
  const [contextError, setContextError] = useState('');
  const [runStatus, setRunStatus] = useState<RunStatus>('idle');
  const [runError, setRunError] = useState('');
  const [steps, setSteps] = useState<AgentStep[]>(createInitialSteps);
  const [sources, setSources] = useState<AgentSource[]>([]);
  const [loopPlan, setLoopPlan] = useState('');
  const [loopTrace, setLoopTrace] = useState<AgentLoopTraceItem[]>([]);
  const [runNotice, setRunNotice] = useState('');
  const [runUsage, setRunUsage] = useState<AgentRunUsage | null>(null);
  const [usageSummary, setUsageSummary] = useState<AgentUsageSummary | null>(null);
  const [usageSummaryLoading, setUsageSummaryLoading] = useState(true);
  const [usageSummaryNotice, setUsageSummaryNotice] = useState('');
  const [usageRuns, setUsageRuns] = useState<AgentUsageRun[]>([]);
  const [usageRunsLoading, setUsageRunsLoading] = useState(true);
  const [usageRunsNotice, setUsageRunsNotice] = useState('');
  const [memoryNotice, setMemoryNotice] = useState('');
  const [memory, setMemory] = useState<AgentMemoryResponse | null>(null);
  const [memoryLoading, setMemoryLoading] = useState(true);
  const [auditEvents, setAuditEvents] = useState<AgentAuditEvent[]>([]);
  const [auditLoading, setAuditLoading] = useState(true);
  const [auditNotice, setAuditNotice] = useState('');
  const [actions, setActions] = useState<AgentAction[]>([]);
  const [actionsLoading, setActionsLoading] = useState(false);
  const [actionNotice, setActionNotice] = useState('');
  const [actionBusyId, setActionBusyId] = useState<string | null>(null);
  const [actionFeedback, setActionFeedback] = useState<Record<string, string>>({});
  const [scheduleDraftForm, setScheduleDraftForm] = useState<ScheduleDraftForm | null>(null);
  const [editingActionId, setEditingActionId] = useState<string | null>(null);
  const [editingActionContent, setEditingActionContent] = useState('');
  const [editingMemoryId, setEditingMemoryId] = useState<string | null>(null);
  const [editingMemoryContent, setEditingMemoryContent] = useState('');
  const [editingMemoryScope, setEditingMemoryScope] = useState('');
  const [trainingTasks, setTrainingTasks] = useState<AgentTrainingTask[]>([]);
  const [trainingTasksLoading, setTrainingTasksLoading] = useState(false);
  const [trainingTaskBusyId, setTrainingTaskBusyId] = useState<string | null>(null);
  const [messages, setMessages] = useState<AgentMessage[]>([createWelcomeMessage()]);
  const [input, setInput] = useState('');
  const latestAssistantMessage = useMemo(
    () => [...messages].reverse().find((message) => message.role === 'assistant' && message.id !== 1 && message.content.trim()),
    [messages],
  );
  const abortRef = useRef<AbortController | null>(null);
  const reconciliationTimerRef = useRef<number | null>(null);
  const chatScrollRef = useRef<HTMLDivElement | null>(null);
  const shouldFollowChatRef = useRef(true);
  const lastRunRequestRef = useRef<AgentRunRequest | null>(null);
  const lastRunMessageIdsRef = useRef<AgentRunMessageIds | null>(null);
  const lastRunActionIntentRef = useRef<AgentActionIntent | null>(null);
  const createConversationRequestRef = useRef<{
    sessionId: string;
    key: string;
    promise?: Promise<AgentConversationSummary>;
  } | null>(null);
  const activeAgentSessionIdRef = useRef<string | null>(agentSessionId);

  useEffect(() => {
    if (routeSessionId) {
      rememberAgentSessionId(routeSessionId);
    } else if (storedSessionId) {
      // 让地址栏也恢复 sessionId，复制链接或刷新时仍然能打开同一份报告。
      setSearchParams((current) => {
        current.set('sessionId', storedSessionId);
        return current;
      }, { replace: true });
    }
    if (routeAgentSessionId) rememberAgentThreadId(routeAgentSessionId);
  }, [routeAgentSessionId, routeSessionId, setSearchParams, storedSessionId]);

  const clearReconciliationTimer = useCallback(() => {
    if (reconciliationTimerRef.current !== null) {
      window.clearTimeout(reconciliationTimerRef.current);
      reconciliationTimerRef.current = null;
    }
  }, []);

  const restoreConversationMessages = useCallback((conversation: AgentConversationResponse, fallbackAssistant?: AgentMessage) => {
    if (conversation.messages.length === 0 && !fallbackAssistant && !conversation.runPartialAnswer) return;
    const restoredMessages: AgentMessage[] = [
      {
        id: 1,
        role: 'assistant',
        content: '你好，我是你的 AI 面试教练。选择一个目标后，我会基于当前面试上下文帮你整理下一步。',
      },
      ...conversation.messages.map((message, index) => ({
        id: index + 2,
        role: message.role,
        content: message.content,
      })),
    ];
    if (conversation.runPartialAnswer?.trim()) {
      restoredMessages.push({
        id: restoredMessages.length + 1,
        role: 'assistant',
        content: conversation.runPartialAnswer,
      });
    }
    if (fallbackAssistant
      && !conversation.messages.some((message) => message.role === 'assistant')
      && !conversation.runPartialAnswer?.trim()) {
      restoredMessages.push(fallbackAssistant);
    }
    setMessages(restoredMessages);
  }, []);

  const restoreRunProgress = useCallback((conversation: AgentConversationResponse) => {
    setSources(conversation.runSources ?? []);
    setLoopPlan(conversation.loopPlan ?? '');
    setLoopTrace(conversation.loopTrace ?? []);
    setRunNotice(conversation.runNotice ?? '');
    setRunUsage(conversation.runUsage ?? null);
    if (!conversation.runStep) return;

    const status = conversation.runStepStatus;
    setSteps((current) => {
      const stepIndex = current.findIndex((step) => step.id === conversation.runStep);
      if (stepIndex < 0) return current;
      return current.map((step, index) => {
      if (index < stepIndex) return { ...step, status: 'completed' };
      if (index > stepIndex) return { ...step, status: 'pending' };
      if (status === 'waiting') return { ...step, status: 'waiting' };
      if (status === 'failed') return { ...step, status: 'failed' };
      if (status === 'cancelled') return { ...step, status: 'cancelled' };
      if (status === 'completed') return { ...step, status: 'completed' };
      return { ...step, status: 'running' };
      });
    });
  }, []);

  const rememberRetryableRequest = useCallback((conversation: AgentConversationResponse) => {
    const retryableRequest = getRetryableRequest(conversation);
    if (!retryableRequest) return;
    lastRunRequestRef.current = retryableRequest;
    lastRunMessageIdsRef.current = null;
  }, []);

  const reconcileConversation = useCallback((
    currentSessionId: string,
    fallbackAssistant?: AgentMessage,
    targetAgentSessionId: string | null = agentSessionId,
  ) => {
    clearReconciliationTimer();
    let attempts = 0;

    const poll = () => {
      attempts += 1;
      void agentApi.getConversation(currentSessionId, targetAgentSessionId)
        .then((conversation) => {
          restoreRunProgress(conversation);
          if (conversation.runStatus === 'RUNNING' && attempts < MAX_RECONCILIATION_ATTEMPTS) {
            setRunStatus('running');
            setRunError('');
            setRunNotice(conversation.runNotice || '连接已断开，但 Agent 仍在后台执行，正在等待任务完成...');
            reconciliationTimerRef.current = window.setTimeout(poll, RECONCILIATION_INTERVAL_MS);
            return;
          }

          clearReconciliationTimer();
          rememberRetryableRequest(conversation);
          restoreConversationMessages(conversation, fallbackAssistant);
          if (conversation.runStatus === 'COMPLETED') {
            setRunError('');
            setRunNotice('Agent 已在后台完成，回答内容已恢复。');
            setRunStatus('completed');
          } else if (conversation.runStatus === 'CANCELLED') {
            setRunError('');
            setRunNotice('本次 Agent 任务已取消，可以重新执行。');
            setRunStatus('cancelled');
          } else if (conversation.runStatus === 'FAILED') {
            setRunNotice('');
            setRunError(conversation.runError || 'Agent 执行失败，请稍后重试');
            setRunStatus('error');
          } else {
            setRunNotice('连接已断开，暂时无法确认 Agent 任务状态，请刷新页面后重试。');
            setRunStatus('error');
          }
        })
        .catch(() => {
          if (attempts < MAX_RECONCILIATION_ATTEMPTS) {
            setRunNotice('暂时无法读取 Agent 状态，正在继续等待...');
            reconciliationTimerRef.current = window.setTimeout(poll, RECONCILIATION_INTERVAL_MS);
            return;
          }
          clearReconciliationTimer();
          setRunError('连接已断开，暂时无法确认 Agent 任务状态，请刷新页面后重试。');
          setRunStatus('error');
        });
    };

    poll();
  }, [agentSessionId, clearReconciliationTimer, rememberRetryableRequest, restoreConversationMessages, restoreRunProgress]);

  useEffect(() => {
    // 同一个页面内切换会话时，先清掉上一份报告的本地状态，避免旧消息和步骤闪现到新会话。
    activeAgentSessionIdRef.current = agentSessionId;
    abortRef.current?.abort();
    abortRef.current = null;
    clearReconciliationTimer();
    lastRunRequestRef.current = null;
    lastRunMessageIdsRef.current = null;
    setRunStatus('idle');
    setRunError('');
    setRunNotice('');
    setSources([]);
    setLoopPlan('');
    setLoopTrace([]);
    setRunUsage(null);
    setActions([]);
    setActionNotice('');
    setActionBusyId(null);
    setActionFeedback({});
    setEditingActionId(null);
    setEditingActionContent('');
    setSteps(createInitialSteps());
    setMessages([createWelcomeMessage()]);
    setInput('');
    if (createConversationRequestRef.current?.sessionId !== sessionId) {
      createConversationRequestRef.current = null;
    }
  }, [agentSessionId, clearReconciliationTimer, sessionId]);

  useEffect(() => {
    // 面试上下文属于报告，不属于 Agent 线程；同一份报告切换线程时必须保留它。
    setInterview(null);
    setContextError('');
    setLoadingContext(needsInterviewContext && Boolean(sessionId));
  }, [needsInterviewContext, sessionId]);

  useEffect(() => {
    if (!needsInterviewContext || !sessionId) {
      setLoadingContext(false);
      return;
    }

    let cancelled = false;
    setLoadingContext(true);
    setContextError('');
    historyApi.getInterviewDetail(sessionId)
      .then((detail) => {
        if (!cancelled) setInterview(detail);
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          const message = getErrorMessage(error);
          const invalidInterviewContext = message === '面试会话不存在'
            || message === '面试会话已过期'
            || message === '禁止访问'
            || message.includes('无权访问');
          if (invalidInterviewContext) {
            // 地址栏可能来自 18083 或旧账号。此时不能让用户继续对着
            // 无效报告点击“新建 Agent 对话”，主动从当前登录环境恢复一份可用报告。
            void interviewApi.listSessions()
              .then((availableSessions) => {
                if (cancelled) return;
                const sorted = [...availableSessions].sort(
                  (left, right) => new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime(),
                );
                const fallback = sorted[0];
                if (fallback && fallback.sessionId !== sessionId) {
                  rememberAgentSessionId(fallback.sessionId);
                  rememberAgentThreadId(null);
                  setContextError('当前链接来自其他环境或已失效，已切换到当前账号最近的面试记录。');
                  setSearchParams({ sessionId: fallback.sessionId }, { replace: true });
                  return;
                }
                if (fallback) {
                  setContextError('当前面试记录暂时无法读取，请刷新后重试。');
                  return;
                }
                // 没有任何可用报告时继续停留在旧 sessionId 会让“新建 Agent 对话”
                // 看起来可点击但永远不会发请求。清掉旧绑定并回到面试中心，
                // 让用户可以选择已有记录或直接创建一场文字面试。
                clearStoredAgentSession();
                navigate('/interview-hub', { replace: true });
              })
              .catch(() => {
                if (!cancelled) setContextError('这份面试记录不在当前环境或已失效，请从当前环境的面试中心重新选择。');
              });
            return;
          }
          setContextError(message || '无法加载面试上下文');
        }
      })
      .finally(() => {
        if (!cancelled) setLoadingContext(false);
      });

    return () => {
      cancelled = true;
    };
  }, [navigate, needsInterviewContext, sessionId, setSearchParams]);

  useEffect(() => {
    if (!isWorkspaceView) return;
    let cancelled = false;
    Promise.all([llmProviderApi.list(), llmProviderApi.getDefaultProvider()])
      .then(([providerList, defaultProvider]) => {
        if (cancelled) return;
        const chatProviders = providerList.filter((provider) => {
          if (!provider.model.trim()) return false;
          return !provider.embeddingModel
            || provider.model.trim().toLowerCase() !== provider.embeddingModel.trim().toLowerCase();
        });
        setProviders(chatProviders);
        setProviderId((current) => current || defaultProvider.defaultProvider || chatProviders[0]?.id || '');
      })
      .catch(() => {
        // Agent 仍可使用后端默认 Provider，不让设置页暂时不可用阻断对话。
      });

    return () => {
      cancelled = true;
    };
  }, [isWorkspaceView]);

  useEffect(() => {
    if (!isWorkspaceView || !sessionId) return;

    // 只有面试报告确认加载成功后，才允许读取/创建其下的 Agent 对话。
    // 否则旧环境或已失效的 sessionId 会让页面显示可操作按钮，随后所有创建请求
    // 都会被后端以“面试会话不存在/无权访问”拒绝。
    if (!interview) return;

    let cancelled = false;
    void (async () => {
      try {
        let conversation = await agentApi.getConversation(sessionId, agentSessionId);
        // 没有线程时保持空状态，不在仅打开报告时偷偷创建“默认线程”。
        // 用户点击“新建 Agent 对话”或真正开始执行任务时再创建，避免首次
        // 打开后再点击新建出现两条空会话。
        if (!conversation.agentSessionId) {
          const existing = await agentApi.listConversations(sessionId);
          const target = existing[0];
          if (target) {
            conversation = await agentApi.getConversation(sessionId, target.agentSessionId);
          }
        }
        if (cancelled) return;

        const resolvedAgentSessionId = conversation.agentSessionId || null;
        if (resolvedAgentSessionId
          && (!routeAgentSessionId || resolvedAgentSessionId !== routeAgentSessionId)) {
          rememberAgentThreadId(resolvedAgentSessionId);
          setSearchParams((current) => {
            current.set('sessionId', sessionId);
            current.set('agentSessionId', resolvedAgentSessionId);
            return current;
          }, { replace: true });
        }

        rememberRetryableRequest(conversation);
        restoreRunProgress(conversation);

        if (conversation.goal === 'review' || conversation.goal === 'practice' || conversation.goal === 'plan') {
          setGoal(conversation.goal);
        }
        if (conversation.providerId) {
          setProviderId(conversation.providerId);
        }
        if (conversation.runStatus === 'RUNNING') {
          setRunStatus('running');
          setRunNotice(conversation.runNotice || '服务端仍有一个 Agent 任务正在执行，可等待完成或取消任务。');
          reconcileConversation(sessionId, undefined, resolvedAgentSessionId);
        }
        if (conversation.runStatus === 'FAILED') {
          setRunStatus('error');
          if (conversation.runError) setRunError(conversation.runError);
        }
        if (conversation.runStatus === 'COMPLETED') setRunStatus('completed');
        if (conversation.runStatus === 'CANCELLED') {
          setRunStatus('cancelled');
          setRunNotice('上一次 Agent 任务已取消，可以重新执行。');
        }
        restoreConversationMessages(conversation);
      } catch {
        // 会话恢复失败不阻塞当前 Agent，仍允许用户从空白上下文开始。
      }
    })();

    return () => {
      cancelled = true;
      clearReconciliationTimer();
    };
  }, [agentSessionId, interview, isWorkspaceView, reconcileConversation, rememberRetryableRequest, restoreConversationMessages, restoreRunProgress, routeAgentSessionId, sessionId, setSearchParams]);

  useEffect(() => {
    if (!isWorkspaceView || !sessionId || !agentSessionId || runStatus !== 'running') return;

    const reconcileWhenActive = () => {
      if (document.visibilityState === 'hidden') return;
      // 某些代理会保持 TCP 连接但停止转发 SSE；重新获得焦点或网络恢复时，
      // 主动读取持久化状态，避免页面长期停在“执行中”。
      reconcileConversation(sessionId, undefined, agentSessionId);
    };
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') reconcileWhenActive();
    };

    window.addEventListener('focus', reconcileWhenActive);
    window.addEventListener('online', reconcileWhenActive);
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => {
      window.removeEventListener('focus', reconcileWhenActive);
      window.removeEventListener('online', reconcileWhenActive);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [agentSessionId, isWorkspaceView, reconcileConversation, runStatus, sessionId]);

  useEffect(() => () => {
    abortRef.current?.abort();
    clearReconciliationTimer();
  }, [clearReconciliationTimer]);

  const selectedGoal = useMemo(
    () => goalOptions.find((option) => option.id === goal) ?? goalOptions[0],
    [goal],
  );
  const showActionManagement = view === 'actions' || (isWorkspaceView && scheduleDraftForm !== null);
  const managementSearch = searchParams.toString() ? `?${searchParams.toString()}` : '';
  const viewSubtitle = view === 'memory'
    ? '沉淀跨面试的训练重点，支持后续复用和维护'
    : view === 'actions'
      ? '集中确认 Agent 草稿，推进专项训练任务'
      : view === 'usage'
        ? '查看 Token、模型调用、耗时、费用和活动轨迹'
        : '把面试报告转成可执行的复盘和训练动作';
  const currentMemoryScope = interview?.skillId?.trim().toLowerCase() || '';
  const applicableMemoryItems = memory?.items.filter((item) => {
    const itemScope = item.jobScope?.trim().toLowerCase() || '';
    return !itemScope || !currentMemoryScope || itemScope === currentMemoryScope;
  }) || [];
  const hasLegacyMemory = Boolean(memory && memory.items.length === 0
    && (memory.weaknesses.length > 0 || memory.recentGoals.length > 0));
  const effectiveMemoryCount = applicableMemoryItems.length
    || (hasLegacyMemory ? memory?.weaknesses.length || 0 : 0);
  const scopedOutMemoryCount = memory && memory.items.length > applicableMemoryItems.length
    ? memory.items.length - applicableMemoryItems.length
    : 0;

  const updateStep = (event: AgentStreamEvent) => {
    if (event.type === 'plan') {
      setLoopPlan(event.content ?? '');
      return;
    }
    if (event.type === 'loop') {
      const iteration = Number.parseInt((event.step ?? '').replace('loop-', ''), 10);
      if (Number.isFinite(iteration) && iteration > 0) {
        setLoopTrace((current) => {
          const previous = current.find((item) => item.iteration === iteration);
          const next: AgentLoopTraceItem = {
            iteration,
            action: event.content ?? previous?.action ?? '',
            status: event.status ?? 'unknown',
            observation: event.status === 'running' ? previous?.observation ?? '' : event.message ?? '',
            attempts: event.attempts ?? previous?.attempts ?? 0,
            durationMillis: event.durationMillis ?? previous?.durationMillis ?? 0,
            failureReason: event.failureReason ?? previous?.failureReason ?? null,
          };
          return [...current.filter((item) => item.iteration !== iteration), next]
            .sort((left, right) => left.iteration - right.iteration);
        });
      }
      if (event.status !== 'running' && event.message) {
        const prefix = event.status === 'completed' ? '执行观察' : '执行异常';
        setLoopPlan((current) => `${current}${current ? '\n\n' : ''}${prefix}：${event.message}`);
      }
      return;
    }
    if (event.type !== 'step' || !event.step || !event.status) return;
    setSteps((current) => {
      const stepIndex = current.findIndex((step) => step.id === event.step);
      if (stepIndex < 0) return current;
      return current.map((step, index) => ({
        ...step,
        status: event.status === 'running'
          ? index < stepIndex ? 'completed' : index === stepIndex ? 'running' : 'pending'
          : index === stepIndex ? event.status! : step.status,
      }));
    });
  };

  const streamAgentMessage = async (
    content: string,
    options?: { goal?: AgentGoal; providerId?: string; retrying?: boolean; actionIntent?: AgentActionIntent | null },
  ): Promise<void> => {
    if (!sessionId || !interview || !content || runStatus === 'running') {
      if (!sessionId || !interview) setRunError('请先从面试详情页进入，并加载一份面试报告');
      return;
    }

    let targetAgentSessionId = agentSessionId;
    if (!targetAgentSessionId) {
      try {
        const created = await createAgentConversation();
        targetAgentSessionId = created?.agentSessionId || null;
      } catch (error: unknown) {
        setRunError(getErrorMessage(error) || '新建 Agent 对话失败，请稍后重试');
        return;
      }
      if (!targetAgentSessionId) {
        setRunError('新建 Agent 对话失败，请稍后重试');
        return;
      }
    }
    activeAgentSessionIdRef.current = targetAgentSessionId;

    const runGoal = options?.goal ?? goal;
    const runProviderId = options?.providerId ?? providerId;
    const actionIntent = options?.actionIntent
      ?? (options?.retrying ? lastRunActionIntentRef.current : null);
    lastRunActionIntentRef.current = actionIntent;
    const runRequest: AgentRunRequest = {
      goal: runGoal,
      message: content,
      providerId: runProviderId || undefined,
      agentSessionId: targetAgentSessionId,
    };
    const historyMessages = options?.retrying
      ? removeLastRunMessages(messages, runRequest, lastRunMessageIdsRef.current)
      : messages;
    const history = buildAgentHistory(historyMessages);
    // 新一轮任务开始时，无论用户之前是否上滑，都把视图带到最新问题和回答。
    shouldFollowChatRef.current = true;
    const userMessageId = Date.now();
    const assistantMessageId = userMessageId + 1;
    const controller = new AbortController();
    abortRef.current?.abort();
    clearReconciliationTimer();
    abortRef.current = controller;

    setInput('');
    setRunError('');
    setRunNotice('');
    setSources([]);
    setLoopPlan('');
    setLoopTrace([]);
    setRunStatus('running');
    setSteps(createInitialSteps().map((step, index) => ({
      ...step,
      status: index === 0 ? 'running' : 'pending',
    })));
    setMessages((current) => [
      ...(options?.retrying
        ? removeLastRunMessages(current, runRequest, lastRunMessageIdsRef.current)
        : current),
      { id: userMessageId, role: 'user', content },
      { id: assistantMessageId, role: 'assistant', content: '' },
    ]);
    lastRunRequestRef.current = runRequest;
    lastRunMessageIdsRef.current = { userMessageId, assistantMessageId };

    void agentApi.streamMessage(
      sessionId,
       { goal: runGoal, message: content, history, providerId: runProviderId || undefined, agentSessionId: targetAgentSessionId },
      {
        onEvent: (event) => {
          // 切换到其他 Agent 会话后，旧会话的 SSE 可能仍在后台完成；
          // 只允许当前会话更新页面，避免并行会话的回答串到一起。
          if (activeAgentSessionIdRef.current !== targetAgentSessionId) return;
          updateStep(event);
          if (event.type === 'step' && event.status === 'waiting') {
            setRunNotice(event.message || '模型服务繁忙，正在等待资源后重试...');
          } else if (event.type === 'step' && event.status === 'running') {
            setRunNotice('');
          }
          if (event.type === 'sources') {
            setSources(event.sources ?? []);
          }
          if (event.type === 'delta' && event.content) {
            setMessages((current) => current.map((message) => message.id === assistantMessageId
              ? { ...message, content: message.content + event.content }
              : message));
          }
          if (event.type === 'error') {
            const message = event.message || 'Agent 执行失败，请稍后重试';
            setRunNotice('');
            setRunError(message);
            setRunStatus('error');
            setMessages((current) => current.map((item) => item.id === assistantMessageId
              ? {
                ...item,
                content: item.content.trim()
                  ? `${item.content}\n\n---\n\n**本次任务未完成**：${message}`
                  : message,
              }
              : item));
          }
          if (event.type === 'done') {
            setRunNotice('');
            setRunStatus((current) => current === 'running' ? 'completed' : current);
          }
        },
        onComplete: () => {
          const isCurrentAgentSession = activeAgentSessionIdRef.current === targetAgentSessionId;
          if (isCurrentAgentSession) {
            setRunStatus((current) => current === 'running' ? 'completed' : current);
          }
          void loadLongTermMemory();
          void loadAuditEvents();
          void loadUsageSummary();
          if (!isCurrentAgentSession) return;
          void agentApi.getConversation(sessionId, targetAgentSessionId)
            .then((conversation) => {
              restoreRunProgress(conversation);
              // 明确的自然语言动作优先，避免“创建训练任务”同时被当前下拉目标再次生成训练计划草稿。
              if (!actionIntent) autoProposeGoalAction(conversation, runGoal);
              autoProposeActionIntent(conversation, actionIntent);
              if (actionIntent?.type === 'schedule') {
                const assistantMessage = [...conversation.messages]
                  .reverse()
                  .find((message) => message.role === 'assistant' && message.content.trim());
                if (assistantMessage && !assistantMessage.content.includes('本次任务未完成')) {
                  openScheduleDraft({
                    id: Date.now(),
                    role: 'assistant',
                    content: assistantMessage.content,
                  }, actionIntent.suggestedTime);
                }
              }
            })
            .catch(() => undefined);
        },
        onError: (error) => {
          if (controller.signal.aborted) return;
          if (activeAgentSessionIdRef.current !== targetAgentSessionId) return;
          const message = getErrorMessage(error) || '连接暂时中断';
          if (error instanceof StreamHttpError && error.status < 500) {
            setRunNotice('本次请求没有提交到后台任务，请根据错误提示调整后重试。');
            setRunError(message);
            setRunStatus('error');
            setMessages((current) => current.map((item) => item.id === assistantMessageId
              ? { ...item, content: message }
              : item));
            return;
          }
          setRunError('');
          setRunStatus('running');
          setRunNotice(`${message}，正在确认后台任务状态...`);
           reconcileConversation(sessionId, {
            id: assistantMessageId,
            role: 'assistant',
            content: 'Agent 连接暂时中断，正在恢复任务状态...',
          });
        },
      },
      controller.signal,
    );
  };

  const loadLongTermMemory = useCallback(() => {
    setMemoryLoading(true);
    return agentApi.getMemory()
      .then((value) => setMemory(value))
      .catch(() => setMemoryNotice('长期记忆读取失败，请稍后重试'))
      .finally(() => setMemoryLoading(false));
  }, []);

  const loadAuditEvents = useCallback(() => {
    setAuditLoading(true);
    setAuditNotice('');
    let attempt = 0;
    const load = (): Promise<void> => agentApi.getAuditEvents(20)
      .then((value) => setAuditEvents(value))
      .catch((error: unknown) => {
        if (attempt < AUDIT_RETRY_DELAYS_MS.length) {
          const delay = AUDIT_RETRY_DELAYS_MS[attempt];
          attempt += 1;
          return new Promise<void>((resolve) => {
            window.setTimeout(resolve, delay);
          }).then(load);
        }
        const message = getErrorMessage(error);
        setAuditNotice(message === '请先登录'
          ? '登录状态已失效，请重新登录后再查看活动记录'
          : '活动记录读取失败，请稍后重试');
      });
    return load()
      .finally(() => setAuditLoading(false));
  }, []);

  const loadUsageSummary = useCallback(() => {
    setUsageSummaryLoading(true);
    setUsageSummaryNotice('');
    return agentApi.getUsageSummary()
      .then((value) => setUsageSummary(value))
      .catch((error: unknown) => {
        setUsageSummaryNotice(getErrorMessage(error) === '请先登录'
          ? '登录状态已失效，请重新登录后查看累计用量'
          : '累计用量读取失败，请稍后重试');
      })
      .finally(() => setUsageSummaryLoading(false));
  }, []);

  const loadUsageRuns = useCallback(() => {
    setUsageRunsLoading(true);
    setUsageRunsNotice('');
    return agentApi.getUsageRuns(20)
      .then((value) => setUsageRuns(value))
      .catch((error: unknown) => {
        setUsageRunsNotice(getErrorMessage(error) === '请先登录'
          ? '登录状态已失效，请重新登录后再查看运行明细'
          : '运行明细读取失败，请稍后重试');
      })
      .finally(() => setUsageRunsLoading(false));
  }, []);

  const loadAgentActions = useCallback(() => {
    if (!sessionId || (isWorkspaceView && !interview)) {
      setActions([]);
      return Promise.resolve();
    }
    setActionsLoading(true);
    return agentApi.listActions(sessionId, agentSessionId)
      .then((value) => setActions(value))
      .catch(() => setActionNotice('待确认动作读取失败，请稍后重试'))
      .finally(() => setActionsLoading(false));
  }, [agentSessionId, interview, isWorkspaceView, sessionId]);

  const loadTrainingTasks = useCallback(() => {
    if (view !== 'actions') {
      setTrainingTasks([]);
      return Promise.resolve();
    }
    setTrainingTasksLoading(true);
    return agentApi.listTrainingTasks()
      .then((value) => setTrainingTasks(value))
      .catch(() => setActionNotice('训练任务读取失败，请稍后重试'))
      .finally(() => setTrainingTasksLoading(false));
  }, [view]);

  useEffect(() => {
    if (view !== 'memory') return;
    void loadLongTermMemory();
  }, [loadLongTermMemory, view]);

  useEffect(() => {
    if (view !== 'usage') return;
    void loadAuditEvents();
  }, [loadAuditEvents, view]);

  useEffect(() => {
    if (view !== 'usage') return;
    void loadUsageSummary();
  }, [loadUsageSummary, view]);

  useEffect(() => {
    if (view !== 'usage') return;
    void loadUsageRuns();
  }, [loadUsageRuns, view]);

  useEffect(() => {
    if (!isWorkspaceView && view !== 'actions') return;
    void loadAgentActions();
  }, [isWorkspaceView, loadAgentActions, view]);

  useEffect(() => {
    if (view !== 'actions') return;
    void loadTrainingTasks();
  }, [loadTrainingTasks, view]);

  const saveDraftAction = (message: AgentMessage, kind: 'plan' | 'practice') => {
    if (!sessionId || !agentSessionId || message.role !== 'assistant' || !message.content.trim()) {
      setActionNotice('当前 Agent 会话还未建立，暂时不能保存动作草稿');
      return;
    }
    const isPractice = kind === 'practice';
    setActionNotice(`正在生成待确认的${isPractice ? '练习题' : '训练计划'}草稿...`);
    const request = isPractice
      ? agentApi.proposePracticeQuestionsDraft
      : agentApi.proposeTrainingPlanDraft;
    void request(
      sessionId, agentSessionId, message.content, stableActionKey(kind, agentSessionId, message.content),
    )
      .then((action) => {
        setActions((current) => [action, ...current.filter((item) => item.actionId !== action.actionId)]);
        setActionNotice(`${isPractice ? '练习题' : '训练计划'}草稿已生成，请在下方确认后保存`);
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || `${isPractice ? '练习题' : '训练计划'}草稿生成失败，请稍后重试`));
  };

  const autoProposeGoalAction = (conversation: AgentConversationResponse, runGoal: AgentGoal) => {
    if ((runGoal !== 'plan' && runGoal !== 'practice') || !conversation.agentSessionId || !sessionId) return;
    const assistantMessage = [...conversation.messages]
      .reverse()
      .find((message) => message.role === 'assistant' && message.content.trim());
    if (!assistantMessage || assistantMessage.content.includes('本次任务未完成')) return;
    const actionRequest = runGoal === 'practice'
      ? agentApi.proposePracticeQuestionsDraft
      : agentApi.proposeTrainingPlanDraft;
    const kind = runGoal === 'practice' ? 'practice' : 'plan';
    void actionRequest(
      sessionId,
      conversation.agentSessionId,
      assistantMessage.content,
      stableActionKey(kind, conversation.agentSessionId, assistantMessage.content),
    )
      .then((action) => {
        setActions((current) => [action, ...current.filter((item) => item.actionId !== action.actionId)]);
        setActionNotice(`本轮已自动生成${action.title}，请确认后保存`);
      })
      .catch((error: unknown) => {
        // 动作建议是附加能力，不能让已完成的 Agent 回答变成失败。
        setActionNotice(getErrorMessage(error) || '动作草稿生成失败，可稍后手动生成');
      });
  };

  const autoProposeActionIntent = (conversation: AgentConversationResponse, actionIntent: AgentActionIntent | null) => {
    if (!actionIntent || actionIntent.type === 'schedule' || !conversation.agentSessionId || !sessionId) return;
    const assistantMessage = [...conversation.messages]
      .reverse()
      .find((message) => message.role === 'assistant' && message.content.trim());
    if (!assistantMessage || assistantMessage.content.includes('本次任务未完成')) return;

    const actionKey = stableActionKey(`intent:${actionIntent.type}`, conversation.agentSessionId, assistantMessage.content);
    let request: Promise<AgentAction>;
    let successMessage: string;
    if (actionIntent.type === 'trainingTask') {
      request = agentApi.proposeTrainingTaskDraft(
        sessionId, conversation.agentSessionId, assistantMessage.content, actionKey,
      );
      successMessage = '训练任务草稿已生成，请确认后创建';
    } else if (actionIntent.type === 'memory') {
      const content = compactMemoryDraft(assistantMessage.content);
      if (!content) return;
      request = agentApi.proposeMemoryDraft(
        sessionId, conversation.agentSessionId, content, 'weakness', interview?.skillId || undefined, actionKey,
      );
      successMessage = '长期记忆草稿已生成，请确认后保存';
    } else if (actionIntent.type === 'title') {
      const title = suggestInterviewTitle(assistantMessage.content, `${selectedGoal.title} · ${interview?.title || '本场面试'}`);
      request = agentApi.proposeInterviewTitleDraft(sessionId, conversation.agentSessionId, title, actionKey);
      successMessage = '面试名称草稿已生成，请确认后应用';
    } else if (actionIntent.type === 'reportExport') {
      request = agentApi.proposeInterviewReportExportDraft(sessionId, conversation.agentSessionId, actionKey);
      successMessage = '报告导出动作已生成，请确认后下载';
    } else {
      if (!interview?.resumeId) {
        setActionNotice('当前面试没有关联简历，暂时不能生成简历优化版');
        return;
      }
      request = agentApi.proposeResumeEditDraft(sessionId, conversation.agentSessionId, assistantMessage.content, actionKey);
      successMessage = '简历优化编辑版已生成，请确认后保存';
    }
    setActionNotice('正在根据你的请求生成待确认动作...');
    void request
      .then((action) => {
        setActions((current) => [action, ...current.filter((item) => item.actionId !== action.actionId)]);
        setActionNotice(successMessage);
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '动作草稿生成失败，可稍后手动生成'));
  };

  const saveMemoryAction = (message: AgentMessage) => {
    if (!sessionId || !agentSessionId || message.role !== 'assistant' || !message.content.trim()) {
      setActionNotice('当前 Agent 会话还未建立，暂时不能保存长期记忆');
      return;
    }
    const content = compactMemoryDraft(message.content);
    if (!content) {
      setActionNotice('这条回答没有可保存的记忆内容');
      return;
    }
    const category = goal === 'plan' ? 'goal' : 'weakness';
    setActionNotice('正在生成待确认的长期记忆草稿...');
    void agentApi.proposeMemoryDraft(
      sessionId,
      agentSessionId,
      content,
      category,
      interview?.skillId || undefined,
      `memory:${agentSessionId}:${message.id}`,
    )
      .then((action) => {
        setActions((current) => [action, ...current.filter((item) => item.actionId !== action.actionId)]);
        setActionNotice('长期记忆草稿已生成，请确认后保存');
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '长期记忆草稿生成失败，请稍后重试'));
  };

  const saveTitleAction = (message: AgentMessage) => {
    if (!sessionId || !agentSessionId || message.role !== 'assistant' || !message.content.trim()) {
      setActionNotice('当前 Agent 会话还未建立，暂时不能建议面试名称');
      return;
    }
    const fallback = `${selectedGoal.title} · ${interview?.title || '本场面试'}`;
    const title = suggestInterviewTitle(message.content, fallback);
    setActionNotice('正在生成待确认的面试名称...');
    void agentApi.proposeInterviewTitleDraft(
      sessionId,
      agentSessionId,
      title,
      `title:${agentSessionId}:${message.id}`,
    )
      .then((action) => {
        setActions((current) => [action, ...current.filter((item) => item.actionId !== action.actionId)]);
        setActionNotice('面试名称草稿已生成，请确认后应用');
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '面试名称草稿生成失败，请稍后重试'));
  };

  const saveTrainingTaskAction = (message: AgentMessage) => {
    if (!sessionId || !agentSessionId || message.role !== 'assistant' || !message.content.trim()) {
      setActionNotice('当前 Agent 会话还未建立，暂时不能创建训练任务');
      return;
    }
    setActionNotice('正在生成待确认的训练任务...');
    void agentApi.proposeTrainingTaskDraft(
      sessionId,
      agentSessionId,
      message.content,
      `task:${agentSessionId}:${message.id}`,
    )
      .then((action) => {
        setActions((current) => [action, ...current.filter((item) => item.actionId !== action.actionId)]);
        setActionNotice('训练任务草稿已生成，请确认后创建');
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '训练任务草稿生成失败，请稍后重试'));
  };

  const saveReportExportAction = (message: AgentMessage) => {
    if (!sessionId || !agentSessionId || message.role !== 'assistant') {
      setActionNotice('当前 Agent 会话还未建立，暂时不能导出面试报告');
      return;
    }
    setActionNotice('正在生成待确认的报告导出动作...');
    void agentApi.proposeInterviewReportExportDraft(
      sessionId,
      agentSessionId,
      `export-report:${agentSessionId}:${message.id}`,
    )
      .then((action) => {
        setActions((current) => [action, ...current.filter((item) => item.actionId !== action.actionId)]);
        setActionNotice('报告导出动作已生成，请确认后下载');
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '报告导出动作生成失败，请稍后重试'));
  };

  const saveResumeEditAction = (message: AgentMessage) => {
    if (!sessionId || !agentSessionId || message.role !== 'assistant' || !message.content.trim()) {
      setActionNotice('当前 Agent 会话还未建立，暂时不能生成简历优化版');
      return;
    }
    const content = compactResumeEditDraft(message.content);
    if (!content) {
      setActionNotice('这条回答没有可保存的简历优化内容');
      return;
    }
    setActionNotice('正在生成待确认的简历优化编辑版...');
    void agentApi.proposeResumeEditDraft(
      sessionId,
      agentSessionId,
      content,
      `resume-edit:${agentSessionId}:${message.id}`,
    )
      .then((action) => {
        setActions((current) => [action, ...current.filter((item) => item.actionId !== action.actionId)]);
        setActionNotice('简历优化编辑版已生成，请确认后保存');
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '简历优化编辑版生成失败，请稍后重试'));
  };

  const openScheduleDraft = (message: AgentMessage, suggestedTime?: string) => {
    if (!sessionId || !agentSessionId || message.role !== 'assistant' || !message.content.trim()) {
      setActionNotice('当前 Agent 会话还未建立，暂时不能安排复习日程');
      return;
    }
    const fallbackTitle = interview?.title || '本场面试复习';
    setScheduleDraftForm({
      messageId: message.id,
      companyName: fallbackTitle,
      position: interview?.skillId || '面试复盘',
      interviewTime: suggestedTime || defaultScheduleTime(),
      notes: `基于 Agent 复盘安排：${message.content.trim().replace(/\s+/g, ' ').slice(0, 1800)}`,
    });
    setActionNotice('请填写复习日程后生成待确认动作');
  };

  const saveScheduleAction = () => {
    if (!sessionId || !agentSessionId || !scheduleDraftForm) {
      setActionNotice('请先填写复习日程');
      return;
    }
    const { messageId, companyName, position, interviewTime, notes } = scheduleDraftForm;
    if (!companyName.trim() || !position.trim() || !interviewTime) {
      setActionNotice('公司或主题、岗位和复习时间不能为空');
      return;
    }
    const payload = {
      companyName: companyName.trim(),
      position: position.trim(),
      interviewTime,
      interviewType: 'OTHER',
      roundNumber: 1,
      notes: notes.trim().slice(0, 2000),
      idempotencyKey: `schedule:${agentSessionId}:${messageId}:${interviewTime}:${position.trim()}`,
    };
    setActionNotice('正在生成待确认的复习日程...');
    void agentApi.proposeInterviewScheduleDraft(sessionId, agentSessionId, payload)
      .then((action) => {
        setActions((current) => [action, ...current.filter((item) => item.actionId !== action.actionId)]);
        setScheduleDraftForm(null);
        setActionNotice('复习日程草稿已生成，请先检查时间和内容再确认');
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '复习日程草稿生成失败，请稍后重试'));
  };

  const confirmAgentAction = (actionId: string) => {
    if (actionBusyId) return;
    const action = actions.find((item) => item.actionId === actionId);
    setActionBusyId(actionId);
    setActionNotice(`正在保存${action?.title || 'Agent 动作'}...`);
    void agentApi.confirmAction(actionId)
      .then((updated) => {
        setActions((current) => current.map((item) => item.actionId === updated.actionId ? updated : item));
        setActionNotice(`${updated.title}已保存，原始面试资料未被修改`);
        if (updated.toolId === 'agent.memory.save') {
          void loadLongTermMemory();
        }
        if (updated.toolId === 'interview.title.rename') {
          setInterview((current) => current ? { ...current, title: updated.draftContent } : current);
        }
        if (updated.toolId === 'training.task.create') {
          void loadTrainingTasks();
        }
        if (isAgentResumeEditAction(updated) && updated.status === 'SUCCEEDED') {
          setActionNotice('简历优化编辑版已保存，请到简历管理中继续编辑或导出');
        }
        if (isAgentReportExportAction(updated) && updated.status === 'SUCCEEDED' && sessionId) {
          setActionNotice('报告导出已确认，正在下载 PDF...');
          void agentApi.downloadConfirmedInterviewReport(updated.actionId)
            .then((blob) => {
              const safeTitle = (interview?.title || '模拟面试报告')
                .replace(/[\\/:*?"<>|\s]+/g, '-')
                .replace(/^-+|-+$/g, '') || '模拟面试报告';
              downloadBlob(blob, `${safeTitle}.pdf`);
              setActionFeedback((current) => ({ ...current, [updated.actionId]: '已下载 PDF' }));
              setActionNotice('面试报告 PDF 已下载');
            })
            .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '报告下载失败，可稍后重试'));
        }
        void loadAuditEvents();
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '动作确认失败，请稍后重试'))
      .finally(() => setActionBusyId(null));
  };

  const updateTrainingTask = (task: AgentTrainingTask, status: AgentTrainingTaskStatus) => {
    if (trainingTaskBusyId) return;
    setTrainingTaskBusyId(task.taskId);
    void agentApi.updateTrainingTask(task.taskId, status)
      .then((updated) => {
        setTrainingTasks((current) => current.map((item) => item.taskId === updated.taskId ? updated : item));
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '训练任务更新失败，请稍后重试'))
      .finally(() => setTrainingTaskBusyId(null));
  };

  const startTrainingTask = (task: AgentTrainingTask) => {
    if (!interview?.skillId || !task.sourceActionId || (task.status !== 'TODO' && task.status !== 'IN_PROGRESS')) return;
    const openFocusedInterview = () => {
      navigate(ROUTES.interviewCreate(createRequestId()), {
        state: {
          interviewConfig: {
            skillId: interview.skillId,
            difficulty: 'mid',
            questionCount: 6,
            llmProvider: providerId || undefined,
            agentActionId: task.sourceActionId,
          },
        },
      });
    };
    if (task.status === 'IN_PROGRESS') {
      openFocusedInterview();
      return;
    }
    if (trainingTaskBusyId) return;
    setTrainingTaskBusyId(task.taskId);
    void agentApi.updateTrainingTask(task.taskId, 'IN_PROGRESS')
      .then((updated) => {
        setTrainingTasks((current) => current.map((item) => item.taskId === updated.taskId ? updated : item));
        openFocusedInterview();
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '训练任务启动失败，请稍后重试'))
      .finally(() => setTrainingTaskBusyId(null));
  };

  const cancelAgentAction = (actionId: string) => {
    if (actionBusyId) return;
    setActionBusyId(actionId);
    void agentApi.cancelAction(actionId)
      .then((updated) => {
        setActions((current) => current.map((item) => item.actionId === updated.actionId ? updated : item));
        setActionNotice('动作已取消，原始面试资料未被修改');
        void loadAuditEvents();
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '动作取消失败，请稍后重试'))
      .finally(() => setActionBusyId(null));
  };

  const retryAgentAction = (actionId: string) => {
    if (actionBusyId) return;
    const action = actions.find((item) => item.actionId === actionId);
    setActionBusyId(actionId);
    setActionNotice(`正在重试${action?.title || 'Agent 动作'}...`);
    void agentApi.retryAction(actionId)
      .then((updated) => {
        setActions((current) => current.map((item) => item.actionId === updated.actionId ? updated : item));
        setActionNotice(updated.status === 'SUCCEEDED'
          ? `${updated.title}已重试成功`
          : `${updated.title}仍未执行成功，请稍后再试`);
        void loadAuditEvents();
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '动作重试失败，请稍后重试'))
      .finally(() => setActionBusyId(null));
  };

  const startFollowUpInterview = (action: AgentAction) => {
    if (!interview?.skillId || action.status !== 'SUCCEEDED') return;
    navigate(ROUTES.interviewCreate(createRequestId()), {
      state: {
        interviewConfig: {
          skillId: interview.skillId,
          difficulty: 'mid',
          questionCount: 6,
          llmProvider: providerId || undefined,
          agentActionId: action.actionId,
        },
      },
    });
  };

  const copyAgentAction = async (action: AgentAction) => {
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(action.draftContent);
      } else {
        const textarea = document.createElement('textarea');
        textarea.value = action.draftContent;
        textarea.setAttribute('readonly', '');
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        const copied = document.execCommand('copy');
        textarea.remove();
        if (!copied) throw new Error('copy failed');
      }
      setActionFeedback((current) => ({ ...current, [action.actionId]: '已复制' }));
    } catch {
      setActionFeedback((current) => ({ ...current, [action.actionId]: '复制失败，请手动选择内容' }));
    }
  };

  const downloadAgentAction = (action: AgentAction) => {
    const safeTitle = action.title.replace(/[\\/:*?"<>|\s]+/g, '-').replace(/^-+|-+$/g, '') || 'agent-草稿';
    const markdown = `# ${action.title}\n\n${action.draftContent.trim()}\n\n---\n\n来源：Agent 动作草稿\n状态：${formatAgentActionStatus(action.status)}\n说明：${action.previewText}`;
    downloadBlob(new Blob([markdown], { type: 'text/markdown;charset=utf-8' }), `${safeTitle}.md`);
    setActionFeedback((current) => ({ ...current, [action.actionId]: '已下载 Markdown' }));
  };

  const downloadInterviewReport = (action: AgentAction) => {
    if (!sessionId) return;
    setActionFeedback((current) => ({ ...current, [action.actionId]: '正在下载...' }));
    void agentApi.downloadConfirmedInterviewReport(action.actionId)
      .then((blob) => {
        const safeTitle = (interview?.title || '模拟面试报告')
          .replace(/[\\/:*?"<>|\s]+/g, '-')
          .replace(/^-+|-+$/g, '') || '模拟面试报告';
        downloadBlob(blob, `${safeTitle}.pdf`);
        setActionFeedback((current) => ({ ...current, [action.actionId]: '已下载 PDF' }));
      })
      .catch((error: unknown) => setActionFeedback((current) => ({
        ...current,
        [action.actionId]: getErrorMessage(error) || '下载失败，请稍后重试',
      })));
  };

  const openResumeEditor = () => {
    if (!interview?.resumeId) return;
    navigate(`/history/${interview.resumeId}`, { state: { initialTab: 'editor' } });
  };

  const startEditAgentAction = (action: AgentAction) => {
    if (action.status !== 'PROPOSED') return;
    setEditingActionId(action.actionId);
    setEditingActionContent(action.draftContent);
    setActionNotice('');
  };

  const cancelEditAgentAction = () => {
    setEditingActionId(null);
    setEditingActionContent('');
  };

  const saveEditedAgentAction = (actionId: string) => {
    if (!editingActionContent.trim()) return;
    setActionNotice('正在保存人工修改...');
    void agentApi.updateActionDraft(actionId, editingActionContent)
      .then((updated) => {
        setActions((current) => current.map((item) => item.actionId === updated.actionId ? updated : item));
        setActionNotice('人工修改已保存，可以继续确认动作');
        cancelEditAgentAction();
        void loadAuditEvents();
      })
      .catch((error: unknown) => setActionNotice(getErrorMessage(error) || '草稿修改失败，请稍后重试'));
  };

  const clearLongTermMemory = () => {
    if (runStatus === 'running' || !window.confirm('确定清除当前浏览器保存的 Agent 长期记忆吗？')) return;
    void agentApi.clearMemory()
      .then(() => {
        setMemory({ lastSkillId: null, weaknesses: [], recentGoals: [], updatedAt: null, items: [] });
        setMemoryNotice('长期记忆已清除');
        void loadAuditEvents();
      })
      .catch(() => setMemoryNotice('长期记忆清除失败，请稍后重试'));
  };

  const startEditMemory = (item: AgentMemoryItem) => {
    if (runStatus === 'running') return;
    setEditingMemoryId(item.id);
    setEditingMemoryContent(item.content);
    setEditingMemoryScope(item.jobScope || '');
    setMemoryNotice('');
  };

  const cancelEditMemory = () => {
    setEditingMemoryId(null);
    setEditingMemoryContent('');
    setEditingMemoryScope('');
  };

  const saveMemoryItem = () => {
    if (!editingMemoryId || !editingMemoryContent.trim()) return;
    void agentApi.updateMemoryItem(editingMemoryId, editingMemoryContent, editingMemoryScope)
      .then((value) => {
        setMemory(value);
        setMemoryNotice('长期记忆已更新');
        void loadAuditEvents();
        cancelEditMemory();
      })
      .catch(() => setMemoryNotice('长期记忆更新失败，请稍后重试'));
  };

  const deleteMemoryItem = (item: AgentMemoryItem) => {
    if (runStatus === 'running' || !window.confirm(`确定删除这条长期记忆吗？\n\n${item.content}`)) return;
    void agentApi.deleteMemoryItem(item.id)
      .then((value) => {
        setMemory(value);
        setMemoryNotice('长期记忆已删除');
        void loadAuditEvents();
        if (editingMemoryId === item.id) cancelEditMemory();
      })
      .catch(() => setMemoryNotice('长期记忆删除失败，请稍后重试'));
  };

  const startRun = () => {
    if (runStatus === 'error' && lastRunRequestRef.current) {
      const lastRequest = lastRunRequestRef.current;
      setGoal(lastRequest.goal);
      streamAgentMessage(lastRequest.message, {
        goal: lastRequest.goal,
        providerId: resolveRetryProviderId(providerId, lastRequest.providerId),
        retrying: true,
      });
      return;
    }
    streamAgentMessage(`开始${selectedGoal.title}`);
  };

  const cancelRun = () => {
    if (!sessionId || runStatus !== 'running') return;
    clearReconciliationTimer();
    abortRef.current?.abort();
    setRunNotice('正在取消 Agent 任务...');
    void agentApi.cancelRun(sessionId, agentSessionId)
      .then((conversation) => {
        if (conversation.runStatus === 'COMPLETED') {
          restoreConversationMessages(conversation);
          setRunError('');
          setRunNotice('Agent 已在取消前完成，回答内容已恢复。');
          setRunStatus('completed');
        } else if (conversation.runStatus === 'FAILED') {
          setRunNotice('');
          setRunError(conversation.runError || 'Agent 执行失败，请稍后重试');
          setRunStatus('error');
        } else {
          setRunStatus('cancelled');
          setRunNotice('本次 Agent 任务已取消，可以重新执行。');
        }
      })
      .catch((error: unknown) => {
        setRunError(getErrorMessage(error) || '取消 Agent 任务失败');
        setRunStatus('error');
      });
  };

  const sendMessage = () => {
    const content = input.trim();
    if (content.length > MAX_AGENT_MESSAGE_CHARS) {
      setRunError(`单条消息不能超过 ${MAX_AGENT_MESSAGE_CHARS} 个字符，请分多次发送`);
      return;
    }
    streamAgentMessage(content, { actionIntent: detectAgentActionIntent(content) });
  };

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      sendMessage();
    }
  };

  const selectAgentSession = (nextSessionId: string) => {
    if (nextSessionId === sessionId) return;
    rememberAgentThreadId(null);
    setSearchParams({ sessionId: nextSessionId });
  };

  const selectAgentConversation = (nextAgentSessionId: string) => {
    if (!sessionId || nextAgentSessionId === agentSessionId) return;
    rememberAgentThreadId(nextAgentSessionId);
    setSearchParams({ sessionId, agentSessionId: nextAgentSessionId });
  };

  const clearAgentConversation = () => {
    if (!sessionId) return;
    rememberAgentThreadId(null);
    setSearchParams({ sessionId });
  };

  const createAgentConversation = async (): Promise<AgentConversationSummary | void> => {
    if (!sessionId || !interview) return;
    const current = createConversationRequestRef.current;
    if (current?.sessionId === sessionId && current.promise) return current.promise;

    const pending = current?.sessionId === sessionId
      ? { sessionId: current.sessionId, key: current.key }
      : { sessionId, key: createRequestId() };
    const request = agentApi.createConversation(sessionId, undefined, pending.key);
    let handledRequest: Promise<AgentConversationSummary>;
    handledRequest = request
      .then((conversation) => {
        if (createConversationRequestRef.current?.key === pending.key) {
          createConversationRequestRef.current = null;
        }
        rememberAgentThreadId(conversation.agentSessionId);
        setSearchParams({ sessionId, agentSessionId: conversation.agentSessionId });
        return conversation;
      })
      .catch((error: unknown) => {
        // 保留同一个键，让按钮重试时可以复用服务端可能已经提交成功的创建请求，
        // 但清掉 promise，避免失败后的重试永远复用已 rejected 的 Promise。
        if (createConversationRequestRef.current?.key === pending.key) {
          createConversationRequestRef.current = pending;
        }
        throw error;
      });
    createConversationRequestRef.current = { ...pending, promise: handledRequest };
    return handledRequest;
  };

  useEffect(() => {
    const container = chatScrollRef.current;
    if (!container || !shouldFollowChatRef.current) return;
    container.scrollTo({ top: container.scrollHeight, behavior: runStatus === 'running' ? 'auto' : 'smooth' });
  }, [messages, runStatus]);

  return (
    <div className="max-w-7xl mx-auto space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/interview-hub')}
            className="p-2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-lg transition-colors"
            aria-label="返回面试中心"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <div className="w-11 h-11 rounded-2xl bg-gradient-to-br from-primary-500 to-indigo-600 text-white flex items-center justify-center shadow-lg shadow-primary-500/25">
            <Bot className="w-6 h-6" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-2xl font-bold text-slate-900 dark:text-white">{viewTitle}</h1>
              <span className="status-badge bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-300 border-primary-100 dark:border-primary-800/50">
                Agent 已接入
              </span>
            </div>
            <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">{viewSubtitle}</p>
          </div>
        </div>
        <div className="flex flex-wrap items-center justify-end gap-2">
          {isWorkspaceView ? <label className="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-2 dark:border-slate-700 dark:bg-slate-800">
            <span className="text-xs dark-text-muted whitespace-nowrap">首选模型</span>
            <span className="relative inline-flex min-w-0 items-center">
              <select
                value={providerId}
                onChange={(event) => setProviderId(event.target.value)}
                disabled={providers.length === 0 || runStatus === 'running'}
                className="w-[190px] appearance-none rounded-lg border border-slate-200 bg-slate-50 pl-3 pr-8 py-1.5 text-sm font-medium text-slate-700 outline-none transition-colors focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20 dark:border-slate-600 dark:bg-slate-700 dark:text-slate-100 disabled:cursor-not-allowed disabled:opacity-60"
                aria-label="选择 Agent 首选模型"
              >
                {providers.length === 0 ? <option value="">使用默认模型</option> : null}
                {providers.map((provider) => (
                  <option key={provider.id} value={provider.id}>
                    {provider.id} · {provider.model}
                  </option>
                ))}
              </select>
              <ChevronDown className="pointer-events-none absolute right-2.5 h-4 w-4 text-slate-400" />
            </span>
          </label> : null}
          <button onClick={() => navigate('/settings')} className="btn-secondary inline-flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm">
            <Settings2 className="w-4 h-4" />
            模型设置
          </button>
          {isWorkspaceView || view === 'memory' ? (
            <button
              onClick={clearLongTermMemory}
              disabled={runStatus === 'running'}
              className="btn-secondary inline-flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm disabled:opacity-50"
            >
              <RotateCcw className="w-4 h-4" />
              清除记忆
            </button>
          ) : null}
        </div>
      </div>
      <AgentNavigation />
      {isWorkspaceView ? <AgentManagementOverview /> : null}
      {isWorkspaceView && (actions.length > 0 || actionNotice) ? (
        <section aria-label="Agent 动作提示" className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-primary-100 bg-primary-50/60 px-4 py-3 dark:border-primary-900/60 dark:bg-primary-900/15">
          <div className="min-w-0">
            <p className="text-sm font-semibold text-primary-800 dark:text-primary-200">动作已移到“动作与训练”页集中处理</p>
            <p className="mt-1 text-xs text-primary-700/80 dark:text-primary-300/80">
              {actionNotice || `当前有 ${actions.length} 个待处理动作，确认后才会写入业务数据。`}
            </p>
          </div>
          <Link
            to={`${ROUTES.agentActions}${managementSearch}`}
            className="inline-flex shrink-0 items-center rounded-lg border border-primary-200 bg-white/80 px-3 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
          >
            去处理动作
          </Link>
        </section>
      ) : null}
      {memoryNotice ? <p className="text-xs text-slate-500 dark:text-slate-400 text-right">{memoryNotice}</p> : null}
      {view === 'memory' ? <section className="dark-card px-5 py-4" aria-label="Agent 长期记忆">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-sm font-semibold dark-text">长期记忆</h2>
              <span className="text-[11px] rounded-full bg-slate-100 px-2 py-0.5 text-slate-500 dark:bg-slate-700 dark:text-slate-300">
                仅保存结构化摘要
              </span>
            </div>
            <p className="text-xs dark-text-muted mt-1">用于跨面试延续训练重点，当前面试报告始终优先。</p>
          </div>
          <button
            onClick={() => void loadLongTermMemory()}
            disabled={memoryLoading || runStatus === 'running'}
            className="btn-secondary inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs disabled:opacity-50"
            aria-label="刷新长期记忆"
          >
            <RotateCcw className={`w-3.5 h-3.5 ${memoryLoading ? 'animate-spin' : ''}`} />
            刷新
          </button>
        </div>
        {memoryLoading ? (
          <p className="text-xs dark-text-muted mt-3">正在读取记忆...</p>
        ) : memory ? (
          <div className="space-y-4 mt-4 text-xs">
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
            <div className="rounded-xl bg-slate-50 dark:bg-slate-800/70 p-3">
              <p className="font-semibold dark-text mb-1">最近方向</p>
              <p className="dark-text-muted break-words">{memory.lastSkillId || '暂无'}</p>
            </div>
            <div className="rounded-xl bg-slate-50 dark:bg-slate-800/70 p-3">
              <p className="font-semibold dark-text mb-1">反复薄弱点</p>
              <p className="dark-text-muted line-clamp-3">{memory.weaknesses.join('；') || '暂无'}</p>
            </div>
            <div className="rounded-xl bg-slate-50 dark:bg-slate-800/70 p-3">
              <p className="font-semibold dark-text mb-1">最近训练目标</p>
              <p className="dark-text-muted line-clamp-3">{memory.recentGoals.join('；') || '暂无'}</p>
            </div>
            </div>
            <div role="region" className="rounded-xl border border-primary-100 bg-primary-50/60 p-4 dark:border-primary-900/60 dark:bg-primary-900/15" aria-label="本场记忆应用预览">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <p className="text-sm font-semibold text-primary-800 dark:text-primary-200">本场面试会怎么使用记忆</p>
                  <p className="mt-1 text-xs leading-5 text-primary-700/80 dark:text-primary-300/80">
                    下一次在当前报告上运行 Agent 时，记忆会作为参考上下文注入；当前面试报告始终优先，不会修改原始报告。
                  </p>
                </div>
                <span className="shrink-0 rounded-full bg-white/80 px-2.5 py-1 text-xs font-medium text-primary-700 dark:bg-slate-800/70 dark:text-primary-300">
                  {interview ? `当前方向：${interview.skillId || '未指定'}` : '未选择面试'}
                </span>
              </div>
              {interview ? (
                <div className="mt-3 grid gap-2 text-xs sm:grid-cols-3">
                  <div className="rounded-lg bg-white/70 p-3 dark:bg-slate-800/60">
                    <p className="font-semibold text-primary-800 dark:text-primary-200">本场生效</p>
                    <p className="mt-1 text-primary-700/80 dark:text-primary-300/80">{effectiveMemoryCount} 条记忆会参与参考</p>
                  </div>
                  <div className="rounded-lg bg-white/70 p-3 dark:bg-slate-800/60">
                    <p className="font-semibold text-primary-800 dark:text-primary-200">作用</p>
                    <p className="mt-1 text-primary-700/80 dark:text-primary-300/80">提醒反复薄弱点和训练目标</p>
                  </div>
                  <div className="rounded-lg bg-white/70 p-3 dark:bg-slate-800/60">
                    <p className="font-semibold text-primary-800 dark:text-primary-200">不会发生</p>
                    <p className="mt-1 text-primary-700/80 dark:text-primary-300/80">不会覆盖本场报告或简历</p>
                  </div>
                </div>
              ) : (
                <p className="mt-3 text-xs text-primary-700/80 dark:text-primary-300/80">从对话工作台选择一份面试报告后，这里会显示本场实际生效的记忆。</p>
              )}
              {interview && applicableMemoryItems.length > 0 ? (
                <div className="mt-3 flex flex-wrap gap-2">
                  {applicableMemoryItems.slice(0, 8).map((item) => (
                    <span key={item.id} className="rounded-full bg-white/80 px-2.5 py-1 text-[11px] text-primary-700 dark:bg-slate-800/70 dark:text-primary-300">
                      {item.category === 'goal' ? '目标' : '薄弱点'}：{item.content}
                    </span>
                  ))}
                </div>
              ) : null}
              {interview && scopedOutMemoryCount > 0 ? (
                <p className="mt-3 text-[11px] text-amber-700/80 dark:text-amber-300/80">
                  另有 {scopedOutMemoryCount} 条记忆的适用方向与本场不一致，本次不会注入。
                </p>
              ) : null}
            </div>
            {memory.items?.length ? (
              <div className="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
                <div className="px-3 py-2 bg-slate-50 dark:bg-slate-800/70 flex items-center justify-between">
                  <p className="font-semibold dark-text">可管理的记忆项</p>
                  <span className="text-[11px] dark-text-muted">可编辑或删除</span>
                </div>
                <div className="divide-y divide-slate-200 dark:divide-slate-700">
                  {memory.items.map((item) => (
                    <div key={item.id} className="px-3 py-3 flex items-start gap-3">
                      {editingMemoryId === item.id ? (
                        <div className="flex-1 space-y-2">
                          <textarea
                            value={editingMemoryContent}
                            onChange={(event) => setEditingMemoryContent(event.target.value)}
                            maxLength={160}
                            rows={2}
                            className="w-full rounded-lg border border-slate-200 bg-white px-2.5 py-2 text-xs dark:border-slate-600 dark:bg-slate-900 dark-text outline-none focus:border-primary-500"
                            aria-label="编辑记忆内容"
                          />
                          <input
                            value={editingMemoryScope}
                            onChange={(event) => setEditingMemoryScope(event.target.value)}
                            maxLength={64}
                            placeholder="适用方向，可选"
                            className="w-full rounded-lg border border-slate-200 bg-white px-2.5 py-2 text-xs dark:border-slate-600 dark:bg-slate-900 dark-text outline-none focus:border-primary-500"
                            aria-label="编辑记忆适用方向"
                          />
                          <div className="flex justify-end gap-2">
                            <button onClick={cancelEditMemory} className="btn-secondary px-2.5 py-1 rounded-lg text-xs">取消</button>
                            <button onClick={saveMemoryItem} disabled={!editingMemoryContent.trim()} className="btn-primary px-2.5 py-1 rounded-lg text-xs disabled:opacity-50">保存</button>
                          </div>
                        </div>
                      ) : (
                        <>
                          <div className="flex-1 min-w-0">
                            <div className="flex flex-wrap items-center gap-2 mb-1">
                              <span className="font-semibold dark-text">{item.category === 'goal' ? '训练目标' : '薄弱点'}</span>
                              {item.jobScope ? <span className="text-[11px] dark-text-muted">适用：{item.jobScope}</span> : null}
                            </div>
                            <p className="dark-text-muted break-words">{item.content}</p>
                            <p className="text-[11px] text-slate-400 mt-1">
                              来源：{item.source || 'Agent'}
                              {item.confidence != null ? ` · 置信度 ${Math.round(item.confidence * 100)}%` : ''}
                              {item.updatedAt ? ` · 更新于 ${formatDate(item.updatedAt)}` : ''}
                              {item.expiresAt ? ` · 有效至 ${formatDate(item.expiresAt)}` : ' · 长期有效'}
                            </p>
                          </div>
                          <div className="flex items-center gap-1 shrink-0">
                            <button onClick={() => startEditMemory(item)} className="p-1.5 rounded-lg text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/20" aria-label={`编辑记忆：${item.content}`}>
                              <Pencil className="w-3.5 h-3.5" />
                            </button>
                            <button onClick={() => deleteMemoryItem(item)} className="p-1.5 rounded-lg text-slate-400 hover:text-rose-500 hover:bg-rose-50 dark:hover:bg-rose-900/20" aria-label={`删除记忆：${item.content}`}>
                              <Trash2 className="w-3.5 h-3.5" />
                            </button>
                          </div>
                        </>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            ) : null}
          </div>
        ) : (
          <p className="text-xs dark-text-muted mt-3">暂无长期记忆，完成一次 Agent 复盘后会自动积累。</p>
        )}
      </section> : null}

      {view === 'usage' ? <section className="dark-card px-5 py-4" aria-label="Agent 累计用量">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-sm font-semibold dark-text">累计用量</h2>
              <span className="text-[11px] rounded-full bg-slate-100 px-2 py-0.5 text-slate-500 dark:bg-slate-700 dark:text-slate-300">
                当前账号
              </span>
            </div>
            <p className="text-xs dark-text-muted mt-1">统计已记录的 Agent 运行，覆盖当前账号下的多个面试和对话。</p>
          </div>
          <button
            onClick={() => void loadUsageSummary()}
            disabled={usageSummaryLoading}
            className="btn-secondary inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs disabled:opacity-50"
            aria-label="刷新 Agent 累计用量"
          >
            <RotateCcw className={`w-3.5 h-3.5 ${usageSummaryLoading ? 'animate-spin' : ''}`} />
            刷新
          </button>
        </div>
        {usageSummaryNotice ? (
          <p className="text-xs text-rose-500 dark:text-rose-300 mt-3">{usageSummaryNotice}</p>
        ) : usageSummaryLoading && !usageSummary ? (
          <p className="text-xs dark-text-muted mt-3">正在读取累计用量...</p>
        ) : usageSummary ? (
          <>
            <div className="mt-4 grid grid-cols-2 gap-3 text-xs sm:grid-cols-4">
              <div className="rounded-xl bg-slate-50 dark:bg-slate-800/70 p-3">
                <span className="dark-text-muted">运行次数</span>
                <p className="mt-1 text-lg font-semibold dark-text">{usageSummary.runCount.toLocaleString()}</p>
                <p className="text-[11px] dark-text-muted mt-1">完成 {usageSummary.completedRunCount} 次</p>
              </div>
              <div className="rounded-xl bg-slate-50 dark:bg-slate-800/70 p-3">
                <span className="dark-text-muted">累计 Token</span>
                <p className="mt-1 text-lg font-semibold dark-text">{usageSummary.totalTokens.toLocaleString()}</p>
                <p className="text-[11px] dark-text-muted mt-1">输入 {usageSummary.promptTokens.toLocaleString()} · 输出 {usageSummary.completionTokens.toLocaleString()}</p>
              </div>
              <div className="rounded-xl bg-slate-50 dark:bg-slate-800/70 p-3">
                <span className="dark-text-muted">模型调用</span>
                <p className="mt-1 text-lg font-semibold dark-text">{usageSummary.modelCalls.toLocaleString()} 次</p>
                <p className="text-[11px] dark-text-muted mt-1">包含重试与降级调用</p>
              </div>
              <div className="rounded-xl bg-slate-50 dark:bg-slate-800/70 p-3">
                <span className="dark-text-muted">累计耗时</span>
                <p className="mt-1 text-lg font-semibold dark-text">{formatDurationMillis(usageSummary.durationMillis)}</p>
                <p className="text-[11px] dark-text-muted mt-1">失败 {usageSummary.failedRunCount} · 取消 {usageSummary.cancelledRunCount}</p>
              </div>
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] dark-text-muted">
              <span>累计费用：</span>
              {usageSummary.costs.length === 0 ? (
                <span>未配置单价</span>
              ) : usageSummary.costs.map((cost) => (
                <span key={`${cost.provider || 'unknown'}-${cost.currency || 'unknown'}`}>
                  {[cost.provider, cost.currency].filter(Boolean).join(' · ') || '未知价格组'} {cost.amount}
                </span>
              ))}
            </div>
          </>
        ) : null}
      </section> : null}

      {view === 'usage' ? <section className="dark-card px-5 py-4" aria-label="Agent 运行明细">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-sm font-semibold dark-text">按运行明细</h2>
              <span className="text-[11px] rounded-full bg-primary-50 px-2 py-0.5 text-primary-600 dark:bg-primary-900/30 dark:text-primary-300">
                可定位到面试和 Agent 会话
              </span>
            </div>
            <p className="text-xs dark-text-muted mt-1">每一行对应一次 Agent 执行，不把重试和其他面试的消耗混在一起。</p>
          </div>
          <button
            onClick={() => void loadUsageRuns()}
            disabled={usageRunsLoading}
            className="btn-secondary inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs disabled:opacity-50"
            aria-label="刷新 Agent 运行明细"
          >
            <RotateCcw className={`w-3.5 h-3.5 ${usageRunsLoading ? 'animate-spin' : ''}`} />
            刷新
          </button>
        </div>
        {usageRunsNotice ? (
          <p className="text-xs text-rose-500 dark:text-rose-300 mt-3">{usageRunsNotice}</p>
        ) : usageRunsLoading ? (
          <p className="text-xs dark-text-muted mt-3">正在读取运行明细...</p>
        ) : usageRuns.length === 0 ? (
          <p className="text-xs dark-text-muted mt-3">还没有可展示的 Agent 运行。完成一次复盘后，这里会显示它具体属于哪场面试。</p>
        ) : (
          <div className="mt-3 space-y-2">
            {usageRuns.map((run) => {
              const href = agentSessionHref(run.interviewSessionId, run.agentSessionId);
              return (
                <div key={run.runId} className="rounded-xl border border-slate-200 bg-slate-50/70 p-3 dark:border-slate-700 dark:bg-slate-800/60">
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        {href ? (
                          <Link to={href} className="truncate text-sm font-semibold text-primary-700 hover:underline dark:text-primary-300">
                            {run.interviewTitle || run.interviewSessionId}
                          </Link>
                        ) : <span className="text-sm font-semibold dark-text">{run.interviewTitle || '未关联面试'}</span>}
                        <span className={`text-xs font-medium ${runStatusClass(run.runStatus)}`}>{formatRunStatus(run.runStatus)}</span>
                      </div>
                      <p className="mt-1 text-xs dark-text-muted">
                        Agent 会话：{run.agentSessionTitle || run.agentSessionId || '未命名会话'}
                        {run.skillId ? ` · 方向：${run.skillId}` : ''}
                      </p>
                    </div>
                    <span className="shrink-0 text-[11px] text-slate-400">{formatDate(run.runFinishedAt || run.createdAt)}</span>
                  </div>
                  <div className="mt-3 grid grid-cols-2 gap-2 text-xs sm:grid-cols-5">
                    <div><span className="dark-text-muted">Token</span><p className="mt-1 font-semibold dark-text">{run.totalTokens.toLocaleString()}</p><p className="text-[11px] dark-text-muted">入 {run.promptTokens.toLocaleString()} · 出 {run.completionTokens.toLocaleString()}</p></div>
                    <div><span className="dark-text-muted">模型调用</span><p className="mt-1 font-semibold dark-text">{run.modelCalls} 次</p></div>
                    <div><span className="dark-text-muted">耗时</span><p className="mt-1 font-semibold dark-text">{formatDurationMillis(run.durationMillis)}</p></div>
                    <div><span className="dark-text-muted">模型</span><p className="mt-1 truncate font-semibold dark-text" title={run.model || undefined}>{run.model || run.provider || '默认模型'}</p></div>
                    <div><span className="dark-text-muted">费用</span><p className="mt-1 font-semibold dark-text">{run.cost == null ? '未计价' : `${run.currency || ''} ${run.cost}`.trim()}</p></div>
                  </div>
                  {href ? <Link to={href} className="mt-3 inline-flex text-xs font-medium text-primary-600 hover:underline dark:text-primary-300">打开这场 Agent 对话</Link> : null}
                </div>
              );
            })}
          </div>
        )}
      </section> : null}

      {view === 'usage' ? <section className="dark-card px-5 py-4" aria-label="Agent 最近活动">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <Activity className="w-4 h-4 text-primary-500" />
            <h2 className="text-sm font-semibold dark-text">活动轨迹</h2>
            <span className="text-[11px] rounded-full bg-slate-100 px-2 py-0.5 text-slate-500 dark:bg-slate-700 dark:text-slate-300">
              可定位来源
            </span>
          </div>
          <button
            onClick={() => void loadAuditEvents()}
            disabled={auditLoading}
            className="btn-secondary inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs disabled:opacity-50"
            aria-label="刷新 Agent 活动记录"
          >
            <RotateCcw className={`w-3.5 h-3.5 ${auditLoading ? 'animate-spin' : ''}`} />
            刷新
          </button>
        </div>
        {auditNotice ? (
          <p className="text-xs text-rose-500 dark:text-rose-300 mt-3">{auditNotice}</p>
        ) : auditLoading ? (
          <p className="text-xs dark-text-muted mt-3">正在读取活动记录...</p>
        ) : auditEvents.length === 0 ? (
          <p className="text-xs dark-text-muted mt-3">暂无活动记录。</p>
        ) : (
          <div className="mt-3 divide-y divide-slate-200 dark:divide-slate-700">
            {auditEvents.slice(0, 8).map((event, index) => {
              const href = agentSessionHref(event.interviewSessionId, event.agentSessionId);
              return (
                <div key={`${event.createdAt}-${event.action}-${index}`} className="flex flex-wrap items-center justify-between gap-3 py-3 text-xs">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="h-1.5 w-1.5 rounded-full bg-primary-400 shrink-0" />
                      <span className="font-medium dark-text">{formatAuditAction(event.action)}</span>
                      <span className="dark-text-muted">{event.resourceType}</span>
                      {event.result ? <span className={event.result === 'success' ? 'text-emerald-600 dark:text-emerald-300' : 'text-rose-600 dark:text-rose-300'}>{event.result === 'success' ? '成功' : event.result}</span> : null}
                    </div>
                    <p className="mt-1 truncate dark-text-muted">
                      面试：{event.interviewTitle || event.interviewSessionId || '未关联面试'}
                      {' · '}Agent 会话：{event.agentSessionTitle || event.agentSessionId || '未关联会话'}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-3">
                    <span className="text-[11px] text-slate-400">{formatDate(event.createdAt)}</span>
                    {href ? <Link to={href} className="font-medium text-primary-600 hover:underline dark:text-primary-300">打开</Link> : null}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section> : null}

      {view === 'workspace' || view === 'actions' ? <div className={`grid grid-cols-1 ${isWorkspaceView ? 'xl:grid-cols-[260px_minmax(0,1fr)]' : ''} gap-6 items-start`}>
        {isWorkspaceView ? <AgentSessionList
          selectedSessionId={sessionId}
          onSelect={selectAgentSession}
          onCreate={() => navigate('/interviews')}
          selectedAgentSessionId={agentSessionId}
          onSelectAgentConversation={selectAgentConversation}
          onCreateAgentConversation={createAgentConversation}
          onClearAgentConversation={clearAgentConversation}
          // 当前线程执行时仍禁止重复提交，但不阻塞创建/切换其他线程。
          agentConversationDisabled={!interview}
        /> : null}

        <div className={`grid grid-cols-1 ${isWorkspaceView ? 'xl:grid-cols-[minmax(0,1.08fr)_minmax(360px,0.92fr)]' : ''} gap-6 items-start min-w-0`}>
        {isWorkspaceView ? <div className="space-y-6 min-w-0">
          <section className="dark-card p-6">
            <div className="flex items-start justify-between gap-4 mb-5">
              <div>
                <p className="text-xs font-semibold text-primary-500 uppercase tracking-wider mb-1">Step 01</p>
                <h2 className="text-lg font-semibold dark-text">你想先解决什么？</h2>
                <p className="text-sm dark-text-muted mt-1">选择目标，Agent 会据此组织后续工作流。</p>
              </div>
              <Target className="w-5 h-5 text-primary-500" />
            </div>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
              {goalOptions.map((option) => {
                const active = option.id === goal;
                return (
                  <button
                    key={option.id}
                    onClick={() => setGoal(option.id)}
                    className={`text-left p-4 rounded-xl border transition-all ${active
                      ? 'border-primary-400 bg-primary-50 dark:border-primary-500 dark:bg-primary-900/25 shadow-sm'
                      : 'border-slate-200 dark:border-slate-700 hover:border-primary-200 dark:hover:border-primary-700 hover:bg-slate-50 dark:hover:bg-slate-800/70'
                      }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className={`text-sm font-semibold ${active ? 'text-primary-600 dark:text-primary-300' : 'dark-text'}`}>
                        {option.title}
                      </span>
                      {active && <Check className="w-4 h-4 text-primary-500" />}
                    </div>
                    <p className="text-xs dark-text-muted mt-2 leading-relaxed">{option.description}</p>
                  </button>
                );
              })}
            </div>
          </section>

          <section className="dark-card p-6">
            <div className="flex items-start justify-between gap-4 mb-5">
              <div>
                <p className="text-xs font-semibold text-primary-500 uppercase tracking-wider mb-1">Context</p>
                <h2 className="text-lg font-semibold dark-text">面试上下文</h2>
                <p className="text-sm dark-text-muted mt-1">Agent 只读取已有数据，不会改变当前面试记录。</p>
              </div>
              <FileText className="w-5 h-5 text-primary-500" />
            </div>

            {loadingContext ? (
              <div className="flex items-center gap-2 text-sm dark-text-muted py-4">
                <Loader2 className="w-4 h-4 animate-spin text-primary-500" />
                正在读取面试报告...
              </div>
            ) : contextError ? (
              <div className="flex items-start gap-3 p-4 rounded-xl bg-rose-50 dark:bg-rose-900/20 text-rose-600 dark:text-rose-300 text-sm">
                <XCircle className="w-5 h-5 shrink-0" />
                <div>
                  <p className="font-medium">面试上下文加载失败</p>
                  <p className="mt-1 opacity-80">{contextError}</p>
                </div>
              </div>
            ) : interview ? (
              <div className="space-y-4">
                <div className="flex flex-wrap items-center gap-3">
                  <div className="flex-1 min-w-[180px] p-4 rounded-xl bg-slate-50 dark:bg-slate-900/60 border border-slate-100 dark:border-slate-700">
                    <p className="text-xs dark-text-muted">当前面试</p>
                    <p className="text-sm font-semibold dark-text mt-1 truncate">#{interview.sessionId.slice(-8)}</p>
                    <p className="text-xs dark-text-muted mt-1">{formatDate(interview.createdAt)}</p>
                  </div>
                  <div className="p-4 rounded-xl bg-slate-50 dark:bg-slate-900/60 border border-slate-100 dark:border-slate-700 min-w-[120px]">
                    <p className="text-xs dark-text-muted">综合评分</p>
                    <p className={`text-2xl font-bold mt-1 ${getScoreTone(interview.overallScore)}`}>
                      {interview.overallScore ?? '--'}
                    </p>
                  </div>
                  <div className="p-4 rounded-xl bg-slate-50 dark:bg-slate-900/60 border border-slate-100 dark:border-slate-700 min-w-[120px]">
                    <p className="text-xs dark-text-muted">回答数量</p>
                    <p className="text-2xl font-bold text-slate-700 dark:text-slate-200 mt-1">{interview.answers.length}</p>
                  </div>
                </div>
                <div className="flex flex-wrap gap-2 text-xs">
                  <span className="px-2.5 py-1 rounded-full bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-300">已接入面试报告</span>
                  <span className="px-2.5 py-1 rounded-full bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-300">只读上下文</span>
                </div>
              </div>
            ) : (
              <div className="flex items-center justify-between gap-4 p-4 rounded-xl bg-slate-50 dark:bg-slate-900/60 border border-dashed border-slate-200 dark:border-slate-700">
                <div>
                  <p className="text-sm font-medium dark-text">还没有选定面试报告</p>
                  <p className="text-xs dark-text-muted mt-1">从面试详情页点击“AI 复盘”，即可自动带入上下文。</p>
                </div>
                <ChevronRight className="w-5 h-5 text-slate-400 shrink-0" />
              </div>
            )}
          </section>

          <section className="dark-card p-6">
            <div className="flex items-start justify-between gap-4 mb-5">
              <div>
                <p className="text-xs font-semibold text-primary-500 uppercase tracking-wider mb-1">Workflow</p>
                <h2 className="text-lg font-semibold dark-text">Agent 工作流</h2>
                <p className="text-sm dark-text-muted mt-1">后端会结合面试报告、Skill 和知识库生成建议。</p>
              </div>
              <Sparkles className="w-5 h-5 text-primary-500" />
            </div>
            <div className="space-y-3">
              {steps.map((step, index) => (
                <div key={step.id} className="flex gap-3">
                  <div className="flex flex-col items-center">
                    <div className={`w-8 h-8 rounded-full flex items-center justify-center shrink-0 ${step.status === 'completed'
                      ? 'bg-emerald-100 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-300'
                      : step.status === 'running'
                        ? 'bg-primary-100 dark:bg-primary-900/40 text-primary-600 dark:text-primary-300'
                        : step.status === 'waiting'
                          ? 'bg-amber-100 dark:bg-amber-900/30 text-amber-600 dark:text-amber-300'
                          : step.status === 'failed'
                            ? 'bg-rose-100 dark:bg-rose-900/30 text-rose-600 dark:text-rose-300'
                            : step.status === 'cancelled'
                              ? 'bg-slate-200 dark:bg-slate-600 text-slate-500 dark:text-slate-300'
                              : 'bg-slate-100 dark:bg-slate-700 text-slate-400'
                      }`}>
                      {step.status === 'completed'
                        ? <CheckCircle2 className="w-4 h-4" />
                        : step.status === 'running'
                          ? <Loader2 className="w-4 h-4 animate-spin" />
                          : step.status === 'waiting'
                            ? <Clock3 className="w-4 h-4" />
                            : step.status === 'failed'
                              ? <XCircle className="w-4 h-4" />
                              : step.status === 'cancelled'
                                ? <XCircle className="w-4 h-4" />
                                : <Circle className="w-4 h-4" />}
                    </div>
                    {index < steps.length - 1 && <div className="w-px flex-1 bg-slate-200 dark:bg-slate-700 my-1" />}
                  </div>
                  <div className="pb-4 pt-1">
                    <p className={`text-sm font-medium ${step.status === 'pending' ? 'text-slate-400 dark:text-slate-500' : 'dark-text'}`}>{step.title}</p>
                    <p className="text-xs dark-text-muted mt-1">{step.description}</p>
                  </div>
                </div>
              ))}
            </div>
            {loopPlan && (
              <div className="mt-4 rounded-xl border border-primary-100 bg-primary-50/70 px-4 py-3 dark:border-primary-900/50 dark:bg-primary-900/20">
                <p className="text-xs font-semibold text-primary-600 dark:text-primary-300">本次执行计划</p>
                <p className="mt-2 whitespace-pre-line text-xs leading-6 text-slate-600 dark:text-slate-300">{loopPlan}</p>
              </div>
            )}
            {loopTrace.length > 0 && (
              <div role="region" className="mt-4 rounded-xl border border-slate-200 bg-white/70 px-4 py-3 dark:border-slate-700 dark:bg-slate-900/30" aria-label="Agent Loop 轨迹">
                <div className="flex items-center justify-between gap-2">
                  <p className="text-xs font-semibold dark-text">Loop 轨迹</p>
                  <span className="text-[11px] dark-text-muted">已记录 {loopTrace.length} 步</span>
                </div>
                <div className="mt-3 space-y-2">
                  {loopTrace.map((item) => (
                    <div key={item.iteration} className="rounded-lg bg-slate-50 px-3 py-2 dark:bg-slate-800/70">
                      <div className="flex flex-wrap items-center gap-2 text-xs">
                        <span className="font-semibold dark-text">第 {item.iteration} 步</span>
                        <span className={item.status === 'completed'
                          ? 'text-emerald-600 dark:text-emerald-300'
                          : item.status === 'running'
                            ? 'text-primary-600 dark:text-primary-300'
                            : 'text-rose-600 dark:text-rose-300'}>
                          {item.status === 'completed' ? '已完成' : item.status === 'running' ? '执行中' : '异常'}
                        </span>
                        {item.attempts > 0 ? <span className="dark-text-muted">尝试 {item.attempts} 次</span> : null}
                        {item.durationMillis > 0 ? <span className="dark-text-muted">耗时 {formatDurationMillis(item.durationMillis)}</span> : null}
                      </div>
                      {item.action ? <p className="mt-1 text-xs dark-text-muted break-words">动作：{item.action}</p> : null}
                      {item.observation ? <p className="mt-1 text-xs leading-5 dark-text-muted break-words">观察：{item.observation}</p> : null}
                      {item.failureReason ? <p className="mt-1 text-xs text-rose-600 dark:text-rose-300 break-words">原因：{item.failureReason}</p> : null}
                    </div>
                  ))}
                </div>
              </div>
            )}
            <div className="flex flex-wrap items-center gap-3 pt-2">
              <button
                onClick={startRun}
                disabled={!sessionId || !interview || runStatus === 'running'}
                className="btn-primary inline-flex items-center gap-2 px-5 py-2.5 rounded-xl text-sm disabled:opacity-60 disabled:cursor-not-allowed"
              >
                {runStatus === 'running' ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
                {runStatus === 'running' ? 'Agent 执行中' : runStatus === 'completed' ? '再次运行' : runStatus === 'error' ? '重试执行' : '开始执行'}
              </button>
              {runStatus === 'running' && (
                <button
                  onClick={cancelRun}
                  className="btn-secondary inline-flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm text-rose-600 dark:text-rose-300"
                >
                  <XCircle className="w-4 h-4" />
                  取消执行
                </button>
              )}
              {(runStatus === 'completed' || runStatus === 'error') && (
              <button onClick={() => { setRunStatus('idle'); setRunError(''); setRunNotice(''); setSources([]); setRunUsage(null); setSteps(createInitialSteps()); }} className="btn-secondary inline-flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm">
                  <RotateCcw className="w-4 h-4" />
                  清空流程
                </button>
              )}
              <span className="text-xs dark-text-muted inline-flex items-center gap-1.5">
                <Clock3 className="w-3.5 h-3.5" />
                通常 1–3 分钟完成，模型繁忙时会自动重试
              </span>
            </div>
            {runNotice && <p className="text-sm text-amber-600 dark:text-amber-300 mt-3">{runNotice}</p>}
            {runError && <p className="text-sm text-rose-500 dark:text-rose-300 mt-3">{runError}</p>}
            {runUsage && (
              <div className="mt-4 rounded-xl border border-slate-200 bg-slate-50/80 px-4 py-3 dark:border-slate-700 dark:bg-slate-800/60" aria-label="本次 Agent 用量">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="text-xs font-semibold dark-text">本次运行用量</p>
                  <p className="text-[11px] dark-text-muted">
                    {[runUsage.provider, runUsage.model].filter(Boolean).join(' · ') || '模型信息暂不可用'}
                  </p>
                </div>
                <div className="mt-3 grid grid-cols-2 gap-2 text-xs sm:grid-cols-4">
                  <div><span className="dark-text-muted">输入 Token</span><p className="font-semibold dark-text">{runUsage.promptTokens.toLocaleString()}</p></div>
                  <div><span className="dark-text-muted">输出 Token</span><p className="font-semibold dark-text">{runUsage.completionTokens.toLocaleString()}</p></div>
                  <div><span className="dark-text-muted">模型调用</span><p className="font-semibold dark-text">{runUsage.modelCalls} 次</p></div>
                  <div><span className="dark-text-muted">模型耗时</span><p className="font-semibold dark-text">{(runUsage.durationMillis / 1000).toFixed(1)} 秒</p></div>
                </div>
                <p className="mt-2 text-[11px] dark-text-muted">
                  总 Token：{runUsage.totalTokens.toLocaleString()} · 费用：{runUsage.cost == null ? '未配置单价' : `${runUsage.cost} ${runUsage.currency || ''}`.trim()}
                </p>
              </div>
            )}
            {sources.length > 0 && (
              <div className="mt-4 pt-4 border-t border-slate-100 dark:border-slate-700">
                <p className="text-xs font-semibold dark-text-muted mb-2">本次参考来源</p>
                <div className="flex flex-wrap gap-2">
                  {sources.map((source) => (
                    <span key={`${source.kind}-${source.reference}`} className="text-xs px-2.5 py-1 rounded-full bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
                      {source.reference} · {source.title}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </section>
        </div> : null}

        <section className="dark-card overflow-hidden xl:sticky xl:top-10 min-w-0">
          {isWorkspaceView ? <>
          <div className="px-6 py-5 border-b border-slate-100 dark:border-slate-700 flex items-center justify-between gap-3">
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 rounded-xl bg-primary-100 dark:bg-primary-900/40 text-primary-600 dark:text-primary-300 flex items-center justify-center">
                <MessageSquare className="w-4 h-4" />
              </div>
              <div>
                <h2 className="font-semibold dark-text">教练对话</h2>
                <p className="text-xs dark-text-muted mt-0.5">围绕「{selectedGoal.title}」继续</p>
              </div>
            </div>
            <span className={`w-2 h-2 rounded-full ${runStatus === 'running' ? 'bg-amber-400 animate-pulse' : 'bg-emerald-400'}`} />
          </div>

          <div
            ref={chatScrollRef}
            onScroll={(event) => {
              const container = event.currentTarget;
              shouldFollowChatRef.current = isNearChatBottom(
                container.scrollTop,
                container.clientHeight,
                container.scrollHeight,
              );
            }}
            className="h-[480px] overflow-y-auto scrollbar-thin p-6 space-y-4"
          >
            <AnimatePresence initial={false}>
              {messages.map((message) => (
                <motion.div
                  key={message.id}
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  className={`flex gap-3 ${message.role === 'user' ? 'justify-end' : ''}`}
                >
                  {message.role === 'assistant' && (
                    <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-primary-500 to-indigo-600 text-white flex items-center justify-center shrink-0">
                      <Bot className="w-4 h-4" />
                    </div>
                  )}
                  <div className={`max-w-[82%] rounded-2xl px-4 py-3 text-sm leading-relaxed ${message.role === 'assistant'
                    ? 'bg-slate-100 dark:bg-slate-700/80 text-slate-700 dark:text-slate-200 rounded-tl-md'
                    : 'bg-primary-600 text-white rounded-tr-md'
                    }`}>
                    {message.role === 'assistant' ? (
                      <>
                        <AgentMarkdown content={message.content} />
                        {message.id !== 1
                          && message.content.trim()
                          && !message.content.includes('本次任务未完成') ? (
                          <div className="mt-3 flex flex-wrap gap-2">
                            <button
                              onClick={() => saveDraftAction(message, goal === 'practice' ? 'practice' : 'plan')}
                              disabled={runStatus === 'running' || !agentSessionId}
                              className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                            >
                              <ClipboardCheck className="w-3.5 h-3.5" />
                              {goal === 'practice' ? '保存为练习题草稿' : '保存为训练计划草稿'}
                            </button>
                            <button
                              onClick={() => saveMemoryAction(message)}
                              disabled={runStatus === 'running' || !agentSessionId}
                              className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                            >
                              <CheckCircle2 className="w-3.5 h-3.5" />
                              保存为长期记忆
                            </button>
                            <button
                              onClick={() => saveTitleAction(message)}
                              disabled={runStatus === 'running' || !agentSessionId}
                              className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                            >
                              <Pencil className="w-3.5 h-3.5" />
                              建议面试名称
                            </button>
                            <button
                              onClick={() => saveTrainingTaskAction(message)}
                              disabled={runStatus === 'running' || !agentSessionId}
                              className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                            >
                              <Target className="w-3.5 h-3.5" />
                              创建训练任务
                            </button>
                            <button
                              onClick={() => saveReportExportAction(message)}
                              disabled={runStatus === 'running' || !agentSessionId}
                              className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                            >
                              <Download className="w-3.5 h-3.5" />
                              导出面试报告
                            </button>
                                    <button
                                      onClick={() => openScheduleDraft(message)}
                                      disabled={runStatus === 'running' || !agentSessionId}
                                      className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                                    >
                                      <Clock3 className="w-3.5 h-3.5" />
                                      安排复习日程
                                    </button>
                            {interview?.resumeId ? (
                              <button
                                onClick={() => saveResumeEditAction(message)}
                                disabled={runStatus === 'running' || !agentSessionId}
                                className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                              >
                                <FileText className="w-3.5 h-3.5" />
                                生成简历优化版
                              </button>
                            ) : null}
                          </div>
                        ) : null}
                      </>
                    ) : (
                      <p className="whitespace-pre-wrap">{message.content}</p>
                    )}
                  </div>
                  {message.role === 'user' && (
                    <div className="w-8 h-8 rounded-lg bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-300 flex items-center justify-center shrink-0">
                      <UserRound className="w-4 h-4" />
                    </div>
                  )}
                </motion.div>
              ))}
            </AnimatePresence>
          </div>
          </> : (
            <div className="border-b border-slate-100 px-6 py-5 dark:border-slate-700">
              <div className="flex items-center gap-3">
                <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-primary-100 text-primary-600 dark:bg-primary-900/40 dark:text-primary-300">
                  <ClipboardCheck className="h-4 w-4" />
                </div>
                <div>
                  <h2 className="font-semibold dark-text">动作与训练管理</h2>
                  <p className="mt-0.5 text-xs dark-text-muted">集中处理 Agent 提议的动作和训练任务，确认后才会写入业务数据。</p>
                </div>
              </div>
            </div>
          )}

          <div className="px-6 pb-6">
            {isWorkspaceView ? <>
            <div className="flex flex-wrap gap-2 mb-3">
              {['我最该先补什么？', '帮我追问这一题', '给我一个练习计划'].map((prompt) => (
                <button key={prompt} onClick={() => setInput(prompt)} className="text-xs px-3 py-1.5 rounded-full border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-300 hover:border-primary-300 hover:text-primary-600 dark:hover:text-primary-300 transition-colors">
                  {prompt}
                </button>
              ))}
            </div>
            <div className="mb-3 rounded-xl border border-primary-100 bg-primary-50/50 p-3 dark:border-primary-900/60 dark:bg-primary-900/10" aria-label="Agent 快捷动作">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <p className="text-xs font-semibold text-primary-800 dark:text-primary-200">快捷动作</p>
                  <p className="mt-1 text-[11px] text-primary-700/80 dark:text-primary-300/80">
                    作用于最近一条 Agent 回答，生成草稿后仍需你确认才会执行。
                  </p>
                </div>
                {!latestAssistantMessage ? <span className="text-[11px] text-slate-400">完成一次回答后可用</span> : null}
              </div>
              <div className="mt-2 flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={() => latestAssistantMessage && saveDraftAction(latestAssistantMessage, goal === 'practice' ? 'practice' : 'plan')}
                  disabled={!latestAssistantMessage || runStatus === 'running' || !agentSessionId}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                >
                  <ClipboardCheck className="h-3.5 w-3.5" />
                  {goal === 'practice' ? '生成练习题草稿' : '生成训练计划草稿'}
                </button>
                <button
                  type="button"
                  onClick={() => latestAssistantMessage && saveTrainingTaskAction(latestAssistantMessage)}
                  disabled={!latestAssistantMessage || runStatus === 'running' || !agentSessionId}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                >
                  <Target className="h-3.5 w-3.5" />
                  创建训练任务
                </button>
                <button
                  type="button"
                  onClick={() => latestAssistantMessage && saveMemoryAction(latestAssistantMessage)}
                  disabled={!latestAssistantMessage || runStatus === 'running' || !agentSessionId}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                >
                  <CheckCircle2 className="h-3.5 w-3.5" />
                  保存为长期记忆
                </button>
                <button
                  type="button"
                  onClick={() => latestAssistantMessage && saveTitleAction(latestAssistantMessage)}
                  disabled={!latestAssistantMessage || runStatus === 'running' || !agentSessionId}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                >
                  <Pencil className="h-3.5 w-3.5" />
                  修改面试名称
                </button>
                <button
                  type="button"
                  onClick={() => latestAssistantMessage && saveReportExportAction(latestAssistantMessage)}
                  disabled={!latestAssistantMessage || runStatus === 'running' || !agentSessionId}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                >
                  <Download className="h-3.5 w-3.5" />
                  导出面试报告
                </button>
                <button
                  type="button"
                  onClick={() => latestAssistantMessage && openScheduleDraft(latestAssistantMessage)}
                  disabled={!latestAssistantMessage || runStatus === 'running' || !agentSessionId}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                >
                  <Clock3 className="h-3.5 w-3.5" />
                  安排复习日程
                </button>
                {interview?.resumeId ? (
                  <button
                    type="button"
                    onClick={() => latestAssistantMessage && saveResumeEditAction(latestAssistantMessage)}
                    disabled={!latestAssistantMessage || runStatus === 'running' || !agentSessionId}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-primary-200 bg-white/80 px-2.5 py-1.5 text-xs font-medium text-primary-700 transition-colors hover:border-primary-400 hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-700 dark:bg-slate-800/70 dark:text-primary-300 dark:hover:bg-primary-900/30"
                  >
                    <FileText className="h-3.5 w-3.5" />
                    生成简历优化版
                  </button>
                ) : null}
              </div>
            </div>
            <div className="relative">
              <textarea
                value={input}
                onChange={(event) => setInput(event.target.value)}
                onKeyDown={handleKeyDown}
                maxLength={MAX_AGENT_MESSAGE_CHARS}
                rows={3}
                placeholder="问问你的面试教练..."
                className="dark-input w-full resize-none rounded-xl px-4 py-3 pr-12 text-sm outline-none"
              />
              <button
                onClick={sendMessage}
                disabled={!input.trim() || !sessionId || !interview || runStatus === 'running'}
                className="absolute right-3 bottom-3 p-2 rounded-lg text-primary-600 dark:text-primary-300 hover:bg-primary-50 dark:hover:bg-primary-900/30 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                aria-label="发送消息"
              >
                <Send className="w-4 h-4" />
              </button>
            </div>
            <p className="text-[11px] text-slate-400 dark:text-slate-500 mt-2">
              Enter 发送，Shift + Enter 换行 · 单条最多 {MAX_AGENT_MESSAGE_CHARS} 字符 · 已输入 {input.length}/{MAX_AGENT_MESSAGE_CHARS}
            </p>
            </> : null}
            {showActionManagement ? <>
            <div className="mt-4 rounded-xl border border-primary-100 bg-primary-50/60 p-3 dark:border-primary-900/60 dark:bg-primary-900/15" aria-label="Agent 动作草稿">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <p className="text-xs font-semibold text-primary-800 dark:text-primary-200">可执行动作草稿</p>
                  <p className="mt-1 text-[11px] text-primary-700/80 dark:text-primary-300/80">先预览，确认后才保存；不会覆盖原始面试报告或简历。</p>
                </div>
                {actionsLoading ? <Loader2 className="h-4 w-4 animate-spin text-primary-500" /> : null}
              </div>
              {actionNotice ? <p className="mt-2 text-xs text-primary-700 dark:text-primary-300">{actionNotice}</p> : null}
              {scheduleDraftForm ? (
                <div className="mt-3 rounded-xl border border-primary-200 bg-primary-50/70 p-3 dark:border-primary-800 dark:bg-primary-900/20" aria-label="复习日程草稿表单">
                  <div className="flex items-center justify-between gap-2">
                    <div>
                      <p className="text-xs font-semibold text-primary-800 dark:text-primary-200">安排复习日程</p>
                      <p className="mt-1 text-[11px] text-primary-700/80 dark:text-primary-300/80">先填写并生成预览，确认后才会写入面试日程。</p>
                    </div>
                    <button
                      type="button"
                      onClick={() => setScheduleDraftForm(null)}
                      className="rounded-lg p-1 text-slate-400 hover:bg-white/70 hover:text-slate-600 dark:hover:bg-slate-800 dark:hover:text-slate-200"
                      aria-label="关闭复习日程表单"
                    >
                      <XCircle className="h-4 w-4" />
                    </button>
                  </div>
                  <div className="mt-3 grid gap-2 sm:grid-cols-2">
                    <label className="text-[11px] text-slate-500 dark:text-slate-300">
                      公司或面试主题
                      <input
                        value={scheduleDraftForm.companyName}
                        onChange={(event) => setScheduleDraftForm((current) => current ? { ...current, companyName: event.target.value } : current)}
                        maxLength={255}
                        className="dark-input mt-1 w-full rounded-lg px-3 py-2 text-xs outline-none"
                      />
                    </label>
                    <label className="text-[11px] text-slate-500 dark:text-slate-300">
                      复习岗位
                      <input
                        value={scheduleDraftForm.position}
                        onChange={(event) => setScheduleDraftForm((current) => current ? { ...current, position: event.target.value } : current)}
                        maxLength={255}
                        className="dark-input mt-1 w-full rounded-lg px-3 py-2 text-xs outline-none"
                      />
                    </label>
                    <label className="text-[11px] text-slate-500 dark:text-slate-300">
                      复习时间
                      <input
                        type="datetime-local"
                        value={scheduleDraftForm.interviewTime}
                        onChange={(event) => setScheduleDraftForm((current) => current ? { ...current, interviewTime: event.target.value } : current)}
                        className="dark-input mt-1 w-full rounded-lg px-3 py-2 text-xs outline-none"
                      />
                    </label>
                    <label className="text-[11px] text-slate-500 dark:text-slate-300">
                      备注
                      <textarea
                        value={scheduleDraftForm.notes}
                        onChange={(event) => setScheduleDraftForm((current) => current ? { ...current, notes: event.target.value } : current)}
                        maxLength={2000}
                        rows={2}
                        className="dark-input mt-1 w-full resize-y rounded-lg px-3 py-2 text-xs leading-5 outline-none"
                      />
                    </label>
                  </div>
                  <div className="mt-3 flex justify-end gap-2">
                    <button type="button" onClick={() => setScheduleDraftForm(null)} className="btn-secondary rounded-lg px-2.5 py-1.5 text-xs">
                      取消
                    </button>
                    <button
                      type="button"
                      onClick={saveScheduleAction}
                      className="btn-primary inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs"
                    >
                      <Clock3 className="h-3.5 w-3.5" />
                      生成待确认日程
                    </button>
                  </div>
                </div>
              ) : null}
              {!actionsLoading && actions.length === 0 ? (
                <p className="mt-3 text-xs text-slate-500 dark:text-slate-400">暂无动作草稿。可从 Agent 回答生成训练计划、长期记忆、面试名称或复习日程。</p>
              ) : null}
              {actions.length > 0 ? (
                <div className="mt-3 space-y-3">
                  {actions.slice(0, 5).map((action) => (
                    <div key={action.actionId} className="rounded-xl border border-white/80 bg-white/80 p-3 dark:border-slate-700 dark:bg-slate-800/80">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex items-center gap-2">
                          <ClipboardCheck className="h-4 w-4 text-primary-500" />
                          <span className="text-xs font-semibold dark-text">{action.title}</span>
                          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-500 dark:bg-slate-700 dark:text-slate-300">
                            {formatAgentActionStatus(action.status)}
                          </span>
                        </div>
                        <span className="text-[11px] text-slate-400">{formatDate(action.createdAt)}</span>
                      </div>
                      <p className="mt-2 text-xs text-slate-600 dark:text-slate-300">{action.previewText}</p>
                      <details className="mt-2" open={editingActionId === action.actionId || undefined}>
                        <summary className="cursor-pointer text-xs font-medium text-primary-600 dark:text-primary-300">查看草稿内容</summary>
                        {editingActionId === action.actionId ? (
                          <div className="mt-2 space-y-2">
                            <textarea
                              value={editingActionContent}
                              onChange={(event) => setEditingActionContent(event.target.value)}
                              maxLength={action.toolId === 'agent.memory.save' ? 160 : action.toolId === 'interview.title.rename' ? 120 : isAgentScheduleAction(action) ? 4000 : 12000}
                              rows={8}
                              className="dark-input w-full resize-y rounded-lg px-3 py-2 text-xs leading-6 outline-none"
                              aria-label={`编辑${action.title}`}
                            />
                            <div className="flex justify-end gap-2">
                              <button onClick={cancelEditAgentAction} className="btn-secondary rounded-lg px-2.5 py-1.5 text-xs">取消</button>
                              <button
                                onClick={() => saveEditedAgentAction(action.actionId)}
                                disabled={!editingActionContent.trim()}
                                className="btn-primary rounded-lg px-2.5 py-1.5 text-xs disabled:opacity-50"
                              >
                                保存修改
                              </button>
                            </div>
                          </div>
                        ) : (
                          <div className="mt-2 max-h-48 overflow-y-auto whitespace-pre-wrap rounded-lg bg-slate-50 p-3 text-xs leading-6 text-slate-600 dark:bg-slate-900/70 dark:text-slate-300">
                            {action.draftContent}
                          </div>
                        )}
                      </details>
                      {action.resultSummary ? <p className={`mt-2 text-xs ${agentActionResultClass(action.status)}`}>{action.resultSummary}</p> : null}
                      {action.status === 'SUCCEEDED'
                        && (action.toolId === 'practice.questions.draft' || action.toolId === 'interview.plan.draft') ? (
                        <button
                          type="button"
                          onClick={() => startFollowUpInterview(action)}
                          className="mt-3 inline-flex items-center gap-1.5 rounded-lg bg-primary-50 px-2.5 py-1.5 text-xs font-medium text-primary-700 hover:bg-primary-100 dark:bg-primary-900/30 dark:text-primary-300 dark:hover:bg-primary-900/50"
                        >
                          <Play className="h-3.5 w-3.5" />
                          {action.toolId === 'interview.plan.draft' ? '按计划开始面试' : '开始专项练习'}
                        </button>
                      ) : null}
                      {action.status === 'SUCCEEDED' && isAgentReportExportAction(action) ? (
                        <button
                          type="button"
                          onClick={() => downloadInterviewReport(action)}
                          className="mt-3 inline-flex items-center gap-1.5 rounded-lg bg-primary-50 px-2.5 py-1.5 text-xs font-medium text-primary-700 hover:bg-primary-100 dark:bg-primary-900/30 dark:text-primary-300 dark:hover:bg-primary-900/50"
                        >
                          <Download className="h-3.5 w-3.5" />
                          下载面试报告 PDF
                        </button>
                      ) : null}
                      {action.status === 'SUCCEEDED' && isAgentResumeEditAction(action) && interview?.resumeId ? (
                        <button
                          onClick={openResumeEditor}
                          className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-200 px-2.5 py-1.5 text-xs text-emerald-700 transition-colors hover:border-emerald-400 hover:bg-emerald-50 dark:border-emerald-700 dark:text-emerald-300 dark:hover:bg-emerald-900/20"
                        >
                          <FileText className="h-3.5 w-3.5" />
                          打开简历编辑
                        </button>
                      ) : null}
                      {action.status === 'SUCCEEDED' && isAgentScheduleAction(action) ? (
                        <button
                          type="button"
                          onClick={() => navigate('/interview-schedule')}
                          className="mt-3 inline-flex items-center gap-1.5 rounded-lg border border-emerald-200 px-2.5 py-1.5 text-xs text-emerald-700 transition-colors hover:border-emerald-400 hover:bg-emerald-50 dark:border-emerald-700 dark:text-emerald-300 dark:hover:bg-emerald-900/20"
                        >
                          <Clock3 className="h-3.5 w-3.5" />
                          打开面试日程
                        </button>
                      ) : null}
                      <div className="mt-3 flex flex-wrap items-center gap-2">
                        <button
                          onClick={() => void copyAgentAction(action)}
                          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs text-slate-600 transition-colors hover:border-primary-300 hover:text-primary-600 dark:border-slate-600 dark:text-slate-300 dark:hover:text-primary-300"
                        >
                          <Copy className="h-3.5 w-3.5" />
                          复制草稿
                        </button>
                        <button
                          onClick={() => downloadAgentAction(action)}
                          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs text-slate-600 transition-colors hover:border-primary-300 hover:text-primary-600 dark:border-slate-600 dark:text-slate-300 dark:hover:text-primary-300"
                        >
                          <Download className="h-3.5 w-3.5" />
                          下载 Markdown
                        </button>
                        {action.status === 'PROPOSED' && editingActionId !== action.actionId ? (
                          <button
                            onClick={() => startEditAgentAction(action)}
                            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs text-slate-600 transition-colors hover:border-primary-300 hover:text-primary-600 dark:border-slate-600 dark:text-slate-300 dark:hover:text-primary-300"
                          >
                            <Pencil className="h-3.5 w-3.5" />
                            {action.toolId === 'interview.title.rename' ? '编辑名称' : action.toolId === 'agent.memory.save' ? '编辑记忆' : isAgentScheduleAction(action) ? '编辑日程' : '编辑草稿'}
                          </button>
                        ) : null}
                        {actionFeedback[action.actionId] ? (
                          <span className="text-[11px] text-emerald-600 dark:text-emerald-300">
                            {actionFeedback[action.actionId]}
                          </span>
                        ) : null}
                      </div>
                      {action.status === 'PROPOSED' ? (
                        <div className="mt-3 flex justify-end gap-2">
                          <button
                            disabled={actionBusyId !== null}
                            onClick={() => cancelAgentAction(action.actionId)}
                            className="btn-secondary rounded-lg px-2.5 py-1.5 text-xs disabled:cursor-not-allowed disabled:opacity-50"
                          >
                            {actionBusyId === action.actionId ? '处理中...' : '取消'}
                          </button>
                          <button
                            disabled={actionBusyId !== null}
                            onClick={() => confirmAgentAction(action.actionId)}
                            className="btn-primary rounded-lg px-2.5 py-1.5 text-xs disabled:cursor-not-allowed disabled:opacity-50"
                          >
                            {actionBusyId === action.actionId
                              ? '处理中...'
                              : action.toolId === 'interview.title.rename' ? '确认应用名称'
                                : action.toolId === 'agent.memory.save' ? '确认保存记忆'
                                  : action.toolId === 'training.task.create' ? '确认创建任务'
                                    : isAgentReportExportAction(action) ? '确认导出报告'
                                      : isAgentResumeEditAction(action) ? '确认保存简历版'
                                      : isAgentScheduleAction(action) ? '确认创建日程'
                                      : '确认保存草稿'}
                          </button>
                        </div>
                      ) : action.status === 'FAILED' ? (
                        <div className="mt-3 flex justify-end">
                          <button
                            disabled={actionBusyId !== null}
                            onClick={() => retryAgentAction(action.actionId)}
                            className="inline-flex items-center gap-1.5 rounded-lg border border-amber-200 px-2.5 py-1.5 text-xs font-medium text-amber-700 hover:bg-amber-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-amber-800 dark:text-amber-300 dark:hover:bg-amber-900/20"
                          >
                            <RotateCcw className="h-3.5 w-3.5" />
                            {actionBusyId === action.actionId ? '重试中...' : '重试动作'}
                          </button>
                        </div>
                      ) : null}
                    </div>
                  ))}
                </div>
              ) : null}
            </div>
            <div className="mt-4 rounded-xl border border-slate-200 bg-white/70 p-3 dark:border-slate-700 dark:bg-slate-800/60" aria-label="Agent 训练任务">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <p className="text-xs font-semibold dark-text">训练任务</p>
                  <p className="mt-1 text-[11px] dark-text-muted">确认 Agent 动作后，任务会进入这里；完成状态由你手动推进。</p>
                </div>
                {trainingTasksLoading ? <Loader2 className="h-4 w-4 animate-spin text-primary-500" /> : null}
              </div>
              {!trainingTasksLoading && trainingTasks.length === 0 ? (
                <p className="mt-3 text-xs dark-text-muted">暂无训练任务。可以从任意一条 Agent 回答创建。</p>
              ) : null}
              {trainingTasks.length > 0 ? (
                <div className="mt-3 space-y-2">
                  {trainingTasks.slice(0, 8).map((task) => (
                    <div key={task.taskId} className="rounded-lg border border-slate-100 bg-slate-50/80 p-3 dark:border-slate-700 dark:bg-slate-900/50">
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <p className="text-xs font-medium dark-text">{task.title}</p>
                          <p className="mt-1 whitespace-pre-wrap text-xs leading-5 dark-text-muted">{task.description}</p>
                        </div>
                        <span className="shrink-0 rounded-full bg-white px-2 py-0.5 text-[11px] text-slate-500 dark:bg-slate-800 dark:text-slate-300">
                          {task.status === 'TODO' ? '待开始' : task.status === 'IN_PROGRESS' ? '进行中' : task.status === 'COMPLETED' ? '已完成' : '已取消'}
                        </span>
                      </div>
                      {task.status === 'TODO' ? (
                        <div className="mt-2 flex gap-2">
                          <button
                            type="button"
                            disabled={trainingTaskBusyId !== null}
                            onClick={() => startTrainingTask(task)}
                            className="btn-primary rounded-lg px-2.5 py-1.5 text-xs disabled:opacity-50"
                          >
                            {trainingTaskBusyId === task.taskId ? '准备中...' : '开始专项面试'}
                          </button>
                          <button
                            type="button"
                            disabled={trainingTaskBusyId !== null}
                            onClick={() => updateTrainingTask(task, 'CANCELLED')}
                            className="btn-secondary rounded-lg px-2.5 py-1.5 text-xs disabled:opacity-50"
                          >
                            取消
                          </button>
                        </div>
                      ) : task.status === 'IN_PROGRESS' ? (
                        <div className="mt-2 flex gap-2">
                          <button
                            type="button"
                            disabled={trainingTaskBusyId !== null}
                            onClick={() => startTrainingTask(task)}
                            className="btn-primary rounded-lg px-2.5 py-1.5 text-xs disabled:opacity-50"
                          >
                            继续专项面试
                          </button>
                          <button
                            type="button"
                            disabled={trainingTaskBusyId !== null}
                            onClick={() => updateTrainingTask(task, 'COMPLETED')}
                            className="btn-primary rounded-lg px-2.5 py-1.5 text-xs disabled:opacity-50"
                          >
                            标记完成
                          </button>
                        </div>
                      ) : null}
                    </div>
                  ))}
                </div>
              ) : null}
            </div>
            </> : null}
          </div>
        </section>
        </div>
      </div> : null}
    </div>
  );
}
