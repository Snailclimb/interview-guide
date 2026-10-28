import { useCallback, useEffect, useRef, useState } from 'react';
import { Archive, FileText, Loader2, MessageSquare, Pencil, Plus, RefreshCw } from 'lucide-react';
import { agentApi } from '../api/agent';
import { interviewApi, type TextSessionMeta } from '../api/interview';
import { getErrorMessage } from '../api/request';
import type { AgentConversationSummary } from '../types/agent';

interface AgentSessionListProps {
  selectedSessionId: string | null;
  disabled?: boolean;
  onSelect: (sessionId: string) => void;
  onCreate: () => void;
  selectedAgentSessionId?: string | null;
  onSelectAgentConversation?: (agentSessionId: string) => void;
  onCreateAgentConversation?: () => Promise<AgentConversationSummary | void>;
  onClearAgentConversation?: () => void;
}

function formatSessionDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '暂无时间';
  return date.toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function getSessionStatus(session: TextSessionMeta) {
  if (session.evaluateStatus === 'PROCESSING' || session.evaluateStatus === 'PENDING') return '评估中';
  if (session.evaluateStatus === 'FAILED') return '评估失败';
  if (session.status === 'IN_PROGRESS') return '面试进行中';
  if (session.status === 'PAUSED') return '面试已暂停';
  if (session.overallScore != null) return `评分 ${session.overallScore}`;
  return '待复盘';
}

function getStatusClass(session: TextSessionMeta) {
  if (session.evaluateStatus === 'FAILED') return 'text-rose-500 dark:text-rose-300';
  if (session.evaluateStatus === 'PROCESSING' || session.evaluateStatus === 'PENDING') {
    return 'text-amber-500 dark:text-amber-300';
  }
  if (session.overallScore != null) return 'text-emerald-500 dark:text-emerald-300';
  return 'text-slate-400 dark:text-slate-500';
}

export default function AgentSessionList({
  selectedSessionId,
  disabled = false,
  onSelect,
  onCreate,
  selectedAgentSessionId = null,
  onSelectAgentConversation,
  onCreateAgentConversation,
  onClearAgentConversation,
}: AgentSessionListProps) {
  const [sessions, setSessions] = useState<TextSessionMeta[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const [conversations, setConversations] = useState<AgentConversationSummary[]>([]);
  const [conversationLoading, setConversationLoading] = useState(false);
  const [conversationError, setConversationError] = useState('');
  const [conversationReloadToken, setConversationReloadToken] = useState(0);
  const [creatingConversation, setCreatingConversation] = useState(false);
  const creatingConversationRef = useRef(false);

  const loadSessions = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true);
    else setLoading(true);
    setError('');

    try {
      const loaded = await interviewApi.listSessions();
      loaded.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
      setSessions(loaded);
    } catch {
      setError('面试会话加载失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    void loadSessions();
  }, [loadSessions]);

  useEffect(() => {
    if (!selectedSessionId) {
      setConversations([]);
      setConversationLoading(false);
      setConversationError('');
      return;
    }
    let cancelled = false;
    setConversations([]);
    setConversationLoading(true);
    setConversationError('');
    agentApi.listConversations(selectedSessionId)
      .then((loaded) => {
        if (!cancelled) setConversations(loaded);
      })
      .catch((loadError: unknown) => {
        if (!cancelled) {
          setConversations([]);
          setConversationError(getErrorMessage(loadError) || 'Agent 对话加载失败');
        }
      })
      .finally(() => {
        if (!cancelled) setConversationLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [conversationReloadToken, selectedSessionId]);

  const createAgentConversation = async () => {
    if (!onCreateAgentConversation || creatingConversationRef.current) return;
    creatingConversationRef.current = true;
    setCreatingConversation(true);
    setConversationError('');
    try {
      const created = await onCreateAgentConversation();
      if (created) {
        setConversations((current) => [
          created,
          ...current.filter((item) => item.agentSessionId !== created.agentSessionId),
        ]);
      }
    } catch (createError: unknown) {
      setConversationError(getErrorMessage(createError) || '新建 Agent 对话失败');
    } finally {
      creatingConversationRef.current = false;
      setCreatingConversation(false);
    }
  };

  const renameConversation = async (conversation: AgentConversationSummary) => {
    const currentTitle = conversation.title?.trim() || '新 Agent 对话';
    const nextTitle = window.prompt('请输入 Agent 会话名称', currentTitle)?.trim();
    if (!nextTitle || nextTitle === currentTitle) return;
    try {
      const updated = await agentApi.renameConversation(
        conversation.interviewSessionId,
        conversation.agentSessionId,
        nextTitle,
      );
      setConversations((current) => current.map((item) => (
        item.agentSessionId === updated.agentSessionId ? updated : item
      )));
    } catch {
      window.alert('Agent 会话名称修改失败，请稍后重试');
    }
  };

  const archiveConversation = async (conversation: AgentConversationSummary) => {
    if (!window.confirm(`确定归档“${conversation.title?.trim() || '新 Agent 对话'}”吗？归档后不会删除历史数据。`)) return;
    try {
      await agentApi.archiveConversation(conversation.interviewSessionId, conversation.agentSessionId);
      setConversations((current) => current.filter((item) => item.agentSessionId !== conversation.agentSessionId));
      if (conversation.agentSessionId === selectedAgentSessionId) {
        const next = conversations.find((item) => item.agentSessionId !== conversation.agentSessionId);
        if (next) onSelectAgentConversation?.(next.agentSessionId);
        else onClearAgentConversation?.();
      }
    } catch {
      window.alert('Agent 会话归档失败，请稍后重试');
    }
  };

  return (
    <aside className="dark-card p-4 xl:sticky xl:top-6">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <p className="text-xs font-semibold text-primary-500 uppercase tracking-wider">Sessions</p>
            <span className="min-w-5 h-5 px-1.5 rounded-full bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-300 text-[11px] font-semibold inline-flex items-center justify-center">
              {sessions.length}
            </span>
          </div>
          <h2 className="text-base font-semibold dark-text mt-1">面试报告与对话</h2>
        </div>
        <button
          type="button"
          onClick={() => void loadSessions(true)}
          disabled={loading || refreshing}
          className="p-2 rounded-lg text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/20 disabled:opacity-50 transition-colors"
          aria-label="刷新 Agent 会话"
          title="刷新会话"
        >
          <RefreshCw className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`} />
        </button>
      </div>
      <p className="text-xs dark-text-muted mt-2 leading-relaxed">
        先选择面试报告，再在同一份报告下切换多个 Agent 对话；每个对话都有独立历史和运行状态。
      </p>

      <button
        type="button"
        onClick={onCreate}
        className="btn-primary w-full mt-4 inline-flex items-center justify-center gap-2 px-3 py-2.5 rounded-xl text-sm"
      >
        <Plus className="w-4 h-4" />
        从面试记录新建
      </button>

      {selectedSessionId && onCreateAgentConversation && (
        <button
          type="button"
          onClick={() => void createAgentConversation()}
          disabled={disabled || creatingConversation}
          className="btn-secondary w-full mt-2 inline-flex items-center justify-center gap-2 px-3 py-2 rounded-xl text-sm disabled:opacity-50"
        >
          {creatingConversation ? <Loader2 className="w-4 h-4 animate-spin" /> : <MessageSquare className="w-4 h-4" />}
          {creatingConversation ? '创建中...' : '新建 Agent 对话'}
        </button>
      )}

      <div className="mt-4 space-y-2 max-h-[min(55vh,560px)] overflow-y-auto pr-1 scrollbar-thin">
        {loading ? (
          <div className="flex items-center justify-center gap-2 py-8 text-sm dark-text-muted">
            <Loader2 className="w-4 h-4 animate-spin text-primary-500" />
            正在加载会话...
          </div>
        ) : error ? (
          <div className="py-6 text-center">
            <p className="text-sm text-rose-500 dark:text-rose-300">{error}</p>
            <button
              type="button"
              onClick={() => void loadSessions(true)}
              className="text-xs text-primary-600 dark:text-primary-300 hover:underline mt-2"
            >
              重新加载
            </button>
          </div>
        ) : sessions.length === 0 ? (
          <div className="py-7 text-center border border-dashed border-slate-200 dark:border-slate-700 rounded-xl">
            <FileText className="w-5 h-5 mx-auto text-slate-400" />
            <p className="text-sm dark-text mt-2">暂无可用会话</p>
            <p className="text-xs dark-text-muted mt-1">先完成一场文字面试，再回来复盘。</p>
          </div>
        ) : (
          <>
          {sessions.map((session) => {
            const active = session.sessionId === selectedSessionId;
            return (
              <button
                key={session.sessionId}
                type="button"
                onClick={() => onSelect(session.sessionId)}
                disabled={disabled}
                className={`w-full text-left p-3 rounded-xl border transition-all ${active
                  ? 'border-primary-400 bg-primary-50 dark:border-primary-500 dark:bg-primary-900/25 shadow-sm'
                  : 'border-slate-200 dark:border-slate-700 hover:border-primary-200 dark:hover:border-primary-700 hover:bg-slate-50 dark:hover:bg-slate-800/70'
                } ${disabled ? 'cursor-not-allowed opacity-60' : ''}`}
                title={disabled ? '当前会话正在执行，请等待任务结束后再切换' : undefined}
              >
                <div className="flex items-start gap-2.5">
                  <FileText className={`w-4 h-4 mt-0.5 shrink-0 ${active ? 'text-primary-500' : 'text-slate-400'}`} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center justify-between gap-2">
                      <p className={`text-sm font-medium truncate ${active ? 'text-primary-700 dark:text-primary-200' : 'dark-text'}`}>
                        {session.title?.trim() || session.skillId || '未命名面试'}
                      </p>
                      {active && <span className="w-1.5 h-1.5 rounded-full bg-primary-500 shrink-0" />}
                    </div>
                    <p className="text-[11px] dark-text-muted mt-1 truncate">#{session.sessionId.slice(-8)}</p>
                    <div className="flex items-center justify-between gap-2 mt-2 text-[11px]">
                      <span className="dark-text-muted">{formatSessionDate(session.createdAt)}</span>
                      <span className={getStatusClass(session)}>{getSessionStatus(session)}</span>
                    </div>
                  </div>
                </div>
              </button>
            );
          })}
          {selectedSessionId && (
            <div className="pt-3 mt-3 border-t border-slate-100 dark:border-slate-700">
              <div className="flex items-center justify-between gap-2 px-1 mb-2">
                <p className="text-[11px] font-semibold uppercase tracking-wider text-primary-500">Agent 对话</p>
                <span className="text-[11px] dark-text-muted">{conversations.length}</span>
              </div>
              {conversationLoading ? (
                <div className="flex items-center gap-2 px-2 py-3 text-xs dark-text-muted">
                  <Loader2 className="w-3.5 h-3.5 animate-spin text-primary-500" />
                  正在加载对话...
                </div>
              ) : conversationError ? (
                <div className="px-2 py-3 text-xs">
                  <p className="text-rose-500 dark:text-rose-300">{conversationError}</p>
                  <button
                    type="button"
                    onClick={() => setConversationReloadToken((current) => current + 1)}
                    className="text-primary-600 dark:text-primary-300 hover:underline mt-1"
                  >
                    重新加载对话
                  </button>
                </div>
              ) : conversations.length === 0 ? (
                <p className="px-2 py-3 text-xs dark-text-muted">还没有 Agent 对话，点击上方按钮新建。</p>
              ) : conversations.map((conversation) => {
                const active = conversation.agentSessionId === selectedAgentSessionId;
                const running = conversation.runStatus === 'RUNNING';
                return (
                  <button
                    key={conversation.agentSessionId}
                    type="button"
                    onClick={() => onSelectAgentConversation?.(conversation.agentSessionId)}
                    disabled={disabled}
                    className={`w-full text-left px-2.5 py-2 rounded-lg border transition-colors ${active
                      ? 'border-primary-300 bg-primary-50 dark:border-primary-600 dark:bg-primary-900/25'
                      : 'border-transparent hover:border-slate-200 hover:bg-slate-50 dark:hover:border-slate-700 dark:hover:bg-slate-800/70'
                    } ${disabled ? 'cursor-not-allowed opacity-60' : ''}`}
                  >
                    <div className="flex items-center gap-2">
                      <MessageSquare className={`w-3.5 h-3.5 shrink-0 ${active ? 'text-primary-500' : 'text-slate-400'}`} />
                      <p className={`text-xs truncate ${active ? 'text-primary-700 dark:text-primary-200 font-medium' : 'dark-text'}`}>
                        {conversation.title?.trim() || '新 Agent 对话'}
                      </p>
                      {running && <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse shrink-0" />}
                      <span
                        role="button"
                        tabIndex={disabled ? -1 : 0}
                        aria-label={`重命名 ${conversation.title || 'Agent 对话'}`}
                        title="重命名对话"
                        onClick={(event) => {
                          event.stopPropagation();
                          if (!disabled) void renameConversation(conversation);
                        }}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            event.stopPropagation();
                            if (!disabled) void renameConversation(conversation);
                          }
                        }}
                        className="ml-auto p-0.5 rounded text-slate-400 hover:text-primary-500 shrink-0"
                      >
                        <Pencil className="w-3 h-3" />
                      </span>
                      <span
                        role="button"
                        tabIndex={disabled ? -1 : 0}
                        aria-label={`归档 ${conversation.title || 'Agent 对话'}`}
                        title="归档对话"
                        onClick={(event) => {
                          event.stopPropagation();
                          if (!disabled) void archiveConversation(conversation);
                        }}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            event.stopPropagation();
                            if (!disabled) void archiveConversation(conversation);
                          }
                        }}
                        className="p-0.5 rounded text-slate-400 hover:text-rose-500 shrink-0"
                      >
                        <Archive className="w-3 h-3" />
                      </span>
                    </div>
                    <p className="text-[10px] dark-text-muted mt-1 pl-5">
                      {conversation.runStatus === 'COMPLETED' ? '已完成' : running ? '执行中' : conversation.runStatus === 'FAILED' ? '执行失败' : '待执行'}
                    </p>
                  </button>
                );
              })}
            </div>
          )}
          </>
        )}
      </div>
    </aside>
  );
}
