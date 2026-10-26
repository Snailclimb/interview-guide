import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  ArrowLeft,
  Bot,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Circle,
  Clock3,
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
import { llmProviderApi } from '../api/llmProvider';
import { getErrorMessage } from '../api/request';
import { StreamHttpError } from '../api/stream';
import CodeBlock from '../components/CodeBlock';
import { getRetryableRequest, removeLastRunMessages, resolveRetryProviderId, type AgentRunMessageIds, type AgentRunRequest } from './agentRun';
import { normalizeAgentMarkdown } from './agentMarkdown';
import { isNearChatBottom } from './agentChatScroll';
import {
  getStoredAgentSessionId,
  getStoredAgentThreadId,
  rememberAgentSessionId,
  rememberAgentThreadId,
  resolveAgentSessionId,
  resolveAgentThreadId,
} from './agentSession';
import type {
  AgentGoal,
  AgentConversationResponse,
  AgentHistoryMessage,
  AgentMemoryItem,
  AgentMemoryResponse,
  AgentRunUsage,
  AgentSource,
  AgentStreamEvent,
} from '../types/agent';
import type { ProviderItem } from '../types/llmProvider';
import AgentSessionList from '../components/AgentSessionList';
type RunStatus = 'idle' | 'running' | 'completed' | 'error' | 'cancelled';
type StepStatus = 'pending' | 'running' | 'waiting' | 'completed' | 'failed' | 'cancelled';

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

export default function AgentPage() {
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
  const [loadingContext, setLoadingContext] = useState(Boolean(sessionId));
  const [contextError, setContextError] = useState('');
  const [runStatus, setRunStatus] = useState<RunStatus>('idle');
  const [runError, setRunError] = useState('');
  const [steps, setSteps] = useState<AgentStep[]>(createInitialSteps);
  const [sources, setSources] = useState<AgentSource[]>([]);
  const [loopPlan, setLoopPlan] = useState('');
  const [runNotice, setRunNotice] = useState('');
  const [runUsage, setRunUsage] = useState<AgentRunUsage | null>(null);
  const [memoryNotice, setMemoryNotice] = useState('');
  const [memory, setMemory] = useState<AgentMemoryResponse | null>(null);
  const [memoryLoading, setMemoryLoading] = useState(true);
  const [editingMemoryId, setEditingMemoryId] = useState<string | null>(null);
  const [editingMemoryContent, setEditingMemoryContent] = useState('');
  const [editingMemoryScope, setEditingMemoryScope] = useState('');
  const [messages, setMessages] = useState<AgentMessage[]>([createWelcomeMessage()]);
  const [input, setInput] = useState('');
  const abortRef = useRef<AbortController | null>(null);
  const reconciliationTimerRef = useRef<number | null>(null);
  const chatScrollRef = useRef<HTMLDivElement | null>(null);
  const shouldFollowChatRef = useRef(true);
  const lastRunRequestRef = useRef<AgentRunRequest | null>(null);
  const lastRunMessageIdsRef = useRef<AgentRunMessageIds | null>(null);

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
    abortRef.current?.abort();
    abortRef.current = null;
    clearReconciliationTimer();
    lastRunRequestRef.current = null;
    lastRunMessageIdsRef.current = null;
    setInterview(null);
    setContextError('');
    setLoadingContext(Boolean(sessionId));
    setRunStatus('idle');
    setRunError('');
    setRunNotice('');
    setSources([]);
    setLoopPlan('');
    setRunUsage(null);
    setSteps(createInitialSteps());
    setMessages([createWelcomeMessage()]);
    setInput('');
  }, [agentSessionId, clearReconciliationTimer, sessionId]);

  useEffect(() => {
    if (!sessionId) {
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
        if (!cancelled) setContextError(getErrorMessage(error) || '无法加载面试上下文');
      })
      .finally(() => {
        if (!cancelled) setLoadingContext(false);
      });

    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  useEffect(() => {
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
  }, []);

  useEffect(() => {
    if (!sessionId) return;

    let cancelled = false;
    void (async () => {
      try {
        let conversation = await agentApi.getConversation(sessionId, agentSessionId);
        // 首次打开某份面试报告时建立一个默认线程，确保第一条消息也能绑定稳定的线程 ID。
        if (!conversation.agentSessionId) {
          const existing = await agentApi.listConversations(sessionId);
          const target = existing[0] ?? await agentApi.createConversation(sessionId);
          conversation = await agentApi.getConversation(sessionId, target.agentSessionId);
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
  }, [agentSessionId, reconcileConversation, rememberRetryableRequest, restoreConversationMessages, restoreRunProgress, routeAgentSessionId, sessionId, setSearchParams]);

  useEffect(() => {
    if (!sessionId || !agentSessionId || runStatus !== 'running') return;

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
  }, [agentSessionId, reconcileConversation, runStatus, sessionId]);

  useEffect(() => () => {
    abortRef.current?.abort();
    clearReconciliationTimer();
  }, [clearReconciliationTimer]);

  const selectedGoal = useMemo(
    () => goalOptions.find((option) => option.id === goal) ?? goalOptions[0],
    [goal],
  );

  const updateStep = (event: AgentStreamEvent) => {
    if (event.type === 'plan') {
      setLoopPlan(event.content ?? '');
      return;
    }
    if (event.type === 'loop' && event.status !== 'running' && event.message) {
      const prefix = event.status === 'completed' ? '执行观察' : '执行异常';
      setLoopPlan((current) => `${current}${current ? '\n\n' : ''}${prefix}：${event.message}`);
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

  const streamAgentMessage = (
    content: string,
    options?: { goal?: AgentGoal; providerId?: string; retrying?: boolean },
  ) => {
    if (!sessionId || !interview || !content || runStatus === 'running') {
      if (!sessionId || !interview) setRunError('请先从面试详情页进入，并加载一份面试报告');
      return;
    }

    const runGoal = options?.goal ?? goal;
    const runProviderId = options?.providerId ?? providerId;
    const runRequest: AgentRunRequest = {
      goal: runGoal,
      message: content,
      providerId: runProviderId || undefined,
      agentSessionId: agentSessionId || undefined,
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
       { goal: runGoal, message: content, history, providerId: runProviderId || undefined, agentSessionId: agentSessionId || undefined },
      {
        onEvent: (event) => {
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
          setRunStatus((current) => current === 'running' ? 'completed' : current);
          void loadLongTermMemory();
          void agentApi.getConversation(sessionId, agentSessionId)
            .then(restoreRunProgress)
            .catch(() => undefined);
        },
        onError: (error) => {
          if (controller.signal.aborted) return;
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

  useEffect(() => {
    void loadLongTermMemory();
  }, [loadLongTermMemory]);

  const clearLongTermMemory = () => {
    if (runStatus === 'running' || !window.confirm('确定清除当前浏览器保存的 Agent 长期记忆吗？')) return;
    void agentApi.clearMemory()
      .then(() => {
        setMemory({ lastSkillId: null, weaknesses: [], recentGoals: [], updatedAt: null, items: [] });
        setMemoryNotice('长期记忆已清除');
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
    streamAgentMessage(content);
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

  const createAgentConversation = () => {
    if (!sessionId || runStatus === 'running') return;
    void agentApi.createConversation(sessionId)
      .then((conversation) => {
        rememberAgentThreadId(conversation.agentSessionId);
        setSearchParams({ sessionId, agentSessionId: conversation.agentSessionId });
      })
      .catch((error: unknown) => {
        setRunError(getErrorMessage(error) || '新建 Agent 对话失败');
      });
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
              <h1 className="text-2xl font-bold text-slate-900 dark:text-white">AI 面试教练</h1>
              <span className="status-badge bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-300 border-primary-100 dark:border-primary-800/50">
                Agent 已接入
              </span>
            </div>
            <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">把面试报告转成可执行的复盘和训练动作</p>
          </div>
        </div>
        <div className="flex flex-wrap items-center justify-end gap-2">
          <label className="inline-flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-2 dark:border-slate-700 dark:bg-slate-800">
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
          </label>
          <button onClick={() => navigate('/settings')} className="btn-secondary inline-flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm">
            <Settings2 className="w-4 h-4" />
            模型设置
          </button>
          <button
            onClick={clearLongTermMemory}
            disabled={runStatus === 'running'}
            className="btn-secondary inline-flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm disabled:opacity-50"
          >
            <RotateCcw className="w-4 h-4" />
            清除记忆
          </button>
        </div>
      </div>
      {memoryNotice ? <p className="text-xs text-slate-500 dark:text-slate-400 text-right">{memoryNotice}</p> : null}
      <section className="dark-card px-5 py-4" aria-label="Agent 长期记忆">
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
        ) : memory && (memory.lastSkillId || memory.weaknesses.length || memory.recentGoals.length || memory.items?.length) ? (
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
                              {item.expiresAt ? ` · ${formatDate(item.expiresAt)} 过期` : ''}
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
      </section>

      <div className="grid grid-cols-1 xl:grid-cols-[260px_minmax(0,1fr)] gap-6 items-start">
        <AgentSessionList
          selectedSessionId={sessionId}
          onSelect={selectAgentSession}
          onCreate={() => navigate('/interviews')}
          selectedAgentSessionId={agentSessionId}
          onSelectAgentConversation={selectAgentConversation}
          onCreateAgentConversation={createAgentConversation}
          onClearAgentConversation={clearAgentConversation}
        />

        <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1.08fr)_minmax(360px,0.92fr)] gap-6 items-start min-w-0">
        <div className="space-y-6 min-w-0">
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
        </div>

        <section className="dark-card overflow-hidden xl:sticky xl:top-10 min-w-0">
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
                      <AgentMarkdown content={message.content} />
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

          <div className="px-6 pb-6">
            <div className="flex flex-wrap gap-2 mb-3">
              {['我最该先补什么？', '帮我追问这一题', '给我一个练习计划'].map((prompt) => (
                <button key={prompt} onClick={() => setInput(prompt)} className="text-xs px-3 py-1.5 rounded-full border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-300 hover:border-primary-300 hover:text-primary-600 dark:hover:text-primary-300 transition-colors">
                  {prompt}
                </button>
              ))}
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
          </div>
        </section>
        </div>
      </div>
    </div>
  );
}
