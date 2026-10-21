import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  ArrowLeft,
  Bot,
  Check,
  CheckCircle2,
  ChevronRight,
  Circle,
  Clock3,
  FileText,
  Loader2,
  MessageSquare,
  Play,
  RotateCcw,
  Send,
  Settings2,
  Sparkles,
  Target,
  UserRound,
  XCircle,
} from 'lucide-react';
import { agentApi } from '../api/agent';
import { historyApi, type InterviewDetail } from '../api/history';
import { llmProviderApi } from '../api/llmProvider';
import { getErrorMessage } from '../api/request';
import CodeBlock from '../components/CodeBlock';
import type {
  AgentGoal,
  AgentHistoryMessage,
  AgentSource,
  AgentStreamEvent,
} from '../types/agent';
import type { ProviderItem } from '../types/llmProvider';
type RunStatus = 'idle' | 'running' | 'completed' | 'error';
type StepStatus = 'pending' | 'running' | 'waiting' | 'completed';

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

const MAX_HISTORY_MESSAGES = 128;

const INLINE_HEADING_TITLES = [
  '本场面试复盘结论',
  '本场面试考察重点',
  '目前无法确认的关键问题',
  '表现概览',
  '关键优势',
  '最重要的改进点',
  '下一步练习',
  '薄弱点判断',
  '答题要点',
  '自测标准',
  '短期目标',
  '分步任务',
  '完成标准',
  '复盘周期',
  '行动建议',
  '改进建议',
  '训练计划',
];

const goalOptions: Array<{ id: AgentGoal; title: string; description: string }> = [
  { id: 'review', title: '复盘本场面试', description: '分析表现，提炼优势和改进点' },
  { id: 'practice', title: '针对性训练', description: '围绕薄弱知识点生成练习题' },
  { id: 'plan', title: '制定训练计划', description: '拆解目标，安排下一步行动' },
];

const createInitialSteps = (): AgentStep[] => [
  { id: 'context', title: '整理面试上下文', description: '读取本场面试的问题、回答和评分', status: 'pending' },
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

/**
 * 模型流式输出偶尔会把标题、列表项和正文压缩到同一行。
 * 只处理普通 Markdown 文本，代码围栏内部保持原样，避免破坏代码内容。
 */
function normalizeAgentMarkdown(content: string) {
  const headingTitles = INLINE_HEADING_TITLES.join('|');
  const inlineHeadingPattern = new RegExp(
    `^(#{1,6}\\s+(?:${headingTitles}))[ \\t]+(?=\\S)`,
    'gm',
  );

  return content
    .replace(/\r\n?/g, '\n')
    .split(/(```[\s\S]*?```)/g)
    .map((part, index) => {
      if (index % 2 === 1) return part;
      return part
        .replace(inlineHeadingPattern, '$1\n\n')
        .replace(/^(\s*(?:[-*+]|\d+\.)\s+\*\*[^*\n]+\*\*)\s*[-：:]\s*/gm, '$1\n   - ')
        .replace(/[ \t]+(?=#{1,6}\s)/g, '\n\n')
        .replace(/([^\n])(?=#{1,6}\s)/g, '$1\n\n')
        .replace(/[ \t]+(?=(?:[-*+] )(?:(?:\*\*)|(?:`)|[A-Za-z\u4e00-\u9fff]))/g, '\n')
        .replace(/[ \t]+(?=\d{1,2}\. (?:(?:\*\*)|(?:`)|[A-Za-z\u4e00-\u9fff]))/g, '\n');
    })
    .join('');
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
  const [searchParams] = useSearchParams();
  const sessionId = searchParams.get('sessionId');
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
  const [runNotice, setRunNotice] = useState('');
  const [messages, setMessages] = useState<AgentMessage[]>([
    {
      id: 1,
      role: 'assistant',
      content: '你好，我是你的 AI 面试教练。选择一个目标后，我会基于当前面试上下文帮你整理下一步。',
    },
  ]);
  const [input, setInput] = useState('');
  const abortRef = useRef<AbortController | null>(null);

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
    agentApi.getConversation(sessionId)
      .then((conversation) => {
        if (cancelled || conversation.messages.length === 0) return;

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
        setMessages(restoredMessages);
        if (conversation.goal === 'review' || conversation.goal === 'practice' || conversation.goal === 'plan') {
          setGoal(conversation.goal);
        }
        if (conversation.providerId) {
          setProviderId(conversation.providerId);
        }
      })
      .catch(() => {
        // 会话恢复失败不阻塞当前 Agent，仍允许用户从空白上下文开始。
      });

    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  useEffect(() => () => {
    abortRef.current?.abort();
  }, []);

  const selectedGoal = useMemo(
    () => goalOptions.find((option) => option.id === goal) ?? goalOptions[0],
    [goal],
  );

  const updateStep = (event: AgentStreamEvent) => {
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

  const streamAgentMessage = (content: string) => {
    if (!sessionId || !interview || !content || runStatus === 'running') {
      if (!sessionId || !interview) setRunError('请先从面试详情页进入，并加载一份面试报告');
      return;
    }

    const history = buildAgentHistory(messages);
    const userMessageId = Date.now();
    const assistantMessageId = userMessageId + 1;
    const controller = new AbortController();
    abortRef.current?.abort();
    abortRef.current = controller;

    setInput('');
    setRunError('');
    setRunNotice('');
    setSources([]);
    setRunStatus('running');
    setSteps(createInitialSteps().map((step, index) => ({
      ...step,
      status: index === 0 ? 'running' : 'pending',
    })));
    setMessages((current) => [
      ...current,
      { id: userMessageId, role: 'user', content },
      { id: assistantMessageId, role: 'assistant', content: '' },
    ]);

    void agentApi.streamMessage(
      sessionId,
      { goal, message: content, history, providerId: providerId || undefined },
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
              ? { ...item, content: message }
              : item));
          }
          if (event.type === 'done') {
            setRunNotice('');
            setRunStatus((current) => current === 'running' ? 'completed' : current);
          }
        },
        onComplete: () => setRunStatus((current) => current === 'running' ? 'completed' : current),
        onError: (error) => {
          if (controller.signal.aborted) return;
          const message = getErrorMessage(error) || 'Agent 执行失败，请稍后重试';
          setRunError(message);
          setRunStatus('error');
          setMessages((current) => current.map((item) => item.id === assistantMessageId
            ? { ...item, content: message }
            : item));
        },
      },
      controller.signal,
    );
  };

  const startRun = () => streamAgentMessage(`开始${selectedGoal.title}`);

  const sendMessage = () => streamAgentMessage(input.trim());

  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      sendMessage();
    }
  };

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
            <select
              value={providerId}
              onChange={(event) => setProviderId(event.target.value)}
              disabled={providers.length === 0 || runStatus === 'running'}
              className="max-w-[190px] bg-transparent text-sm font-medium dark-text outline-none disabled:opacity-60"
              aria-label="选择 Agent 首选模型"
            >
              {providers.length === 0 ? <option value="">使用默认模型</option> : null}
              {providers.map((provider) => (
                <option key={provider.id} value={provider.id}>
                  {provider.id} · {provider.model}
                </option>
              ))}
            </select>
          </label>
          <button onClick={() => navigate('/settings')} className="btn-secondary inline-flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm">
            <Settings2 className="w-4 h-4" />
            模型设置
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1.08fr)_minmax(360px,0.92fr)] gap-6 items-start">
        <div className="space-y-6">
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
                        : 'bg-slate-100 dark:bg-slate-700 text-slate-400'
                      }`}>
                      {step.status === 'completed' ? <CheckCircle2 className="w-4 h-4" /> : step.status === 'running' ? <Loader2 className="w-4 h-4 animate-spin" /> : step.status === 'waiting' ? <Clock3 className="w-4 h-4" /> : <Circle className="w-4 h-4" />}
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
            <div className="flex flex-wrap items-center gap-3 pt-2">
              <button
                onClick={startRun}
                disabled={!sessionId || !interview || runStatus === 'running'}
                className="btn-primary inline-flex items-center gap-2 px-5 py-2.5 rounded-xl text-sm disabled:opacity-60 disabled:cursor-not-allowed"
              >
                {runStatus === 'running' ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
                {runStatus === 'running' ? 'Agent 执行中' : runStatus === 'completed' ? '再次运行' : runStatus === 'error' ? '重试执行' : '开始执行'}
              </button>
              {(runStatus === 'completed' || runStatus === 'error') && (
                <button onClick={() => { setRunStatus('idle'); setRunError(''); setRunNotice(''); setSources([]); setSteps(createInitialSteps()); }} className="btn-secondary inline-flex items-center gap-2 px-4 py-2.5 rounded-xl text-sm">
                  <RotateCcw className="w-4 h-4" />
                  清空流程
                </button>
              )}
              <span className="text-xs dark-text-muted inline-flex items-center gap-1.5">
                <Clock3 className="w-3.5 h-3.5" />
                预计 1 分钟内完成
              </span>
            </div>
            {runNotice && <p className="text-sm text-amber-600 dark:text-amber-300 mt-3">{runNotice}</p>}
            {runError && <p className="text-sm text-rose-500 dark:text-rose-300 mt-3">{runError}</p>}
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

        <section className="dark-card overflow-hidden xl:sticky xl:top-10">
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

          <div className="h-[480px] overflow-y-auto scrollbar-thin p-6 space-y-4">
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
            <p className="text-[11px] text-slate-400 dark:text-slate-500 mt-2">Enter 发送，Shift + Enter 换行 · 回答使用系统默认模型</p>
          </div>
        </section>
      </div>
    </div>
  );
}
