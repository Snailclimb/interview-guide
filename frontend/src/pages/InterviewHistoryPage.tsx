import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {AnimatePresence, motion} from 'framer-motion';
import {CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis} from 'recharts';
import {historyApi} from '../api/history';
import {interviewApi, type TextSessionMeta} from '../api/interview';
import {voiceInterviewApi, SessionMeta} from '../api/voiceInterview';
import {formatDate, formatDateOnly} from '../utils/date';
import {getScoreProgressColor} from '../utils/score';
import {
  calculateInterviewStats,
  getKnowledgeBaseInterviewCategoryLabel,
  isCompletedStatus,
  isEvaluateCompleted,
} from './interviewHistoryStats.ts';
import {
  getVoiceEvaluationPresentation,
  shouldRefreshVoiceEvaluationPresentation,
} from './voiceEvaluationStatus.ts';
import {skillApi, type SkillDTO} from '../api/skill';
import {getTemplateName} from '../utils/voiceInterview';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import {
  AlertCircle,
  CheckCircle,
  ChevronRight,
  Clock,
  Download,
  FileText,
  Filter,
  Loader2,
  Mic,
  Pencil,
  PlayCircle,
  RefreshCw,
  RotateCcw,
  Search,
  Tag,
  Trash2,
  TrendingUp,
  Users,
  XCircle,
} from 'lucide-react';

type InterviewType = 'all' | 'text' | 'voice';
type TimeRange = 'all' | '7d' | '30d' | '90d';
type CompletionFilter = 'all' | 'inProgress' | 'completed';

const TIME_RANGE_OPTIONS: { value: TimeRange; label: string }[] = [
  { value: 'all', label: '全部时间' },
  { value: '7d', label: '最近 7 天' },
  { value: '30d', label: '最近 30 天' },
  { value: '90d', label: '最近 90 天' },
];

const COMPLETION_OPTIONS: { value: CompletionFilter; label: string }[] = [
  { value: 'all', label: '全部状态' },
  { value: 'inProgress', label: '进行中' },
  { value: 'completed', label: '已完成' },
];

const ALL_DIRECTIONS_LABEL = '全部方向';
const ALL_DIRECTIONS_VALUE = '__all_directions__';

interface UnifiedInterviewItem {
  id: string;
  type: 'text' | 'voice';
  sourceType?: string | null;
  title: string;
  sessionId: string;
  status: string;
  evaluateStatus?: string;
  evaluateError?: string;
  evaluateStatusUpdatedAt?: string;
  overallScore: number | null;
  totalQuestions?: number;
  actualDuration?: number;
  createdAt: string;
  resumeId?: number;
  knowledgeBaseId?: number;
  interviewCategory?: string | null;
  voiceSessionId?: number;
}

function isLiveStatus(status: string): boolean {
  return status === 'IN_PROGRESS' || status === 'PAUSED';
}

function isEvaluating(item: UnifiedInterviewItem): boolean {
  return item.evaluateStatus === 'PENDING' || item.evaluateStatus === 'PROCESSING';
}

function isEvaluateFailed(item: UnifiedInterviewItem): boolean {
  return item.evaluateStatus === 'FAILED';
}

function getItemEvaluationPresentation(item: UnifiedInterviewItem) {
  return getVoiceEvaluationPresentation({
    status: item.evaluateStatus,
    statusUpdatedAt: item.evaluateStatusUpdatedAt,
  });
}

function isVoiceEvaluationRetryable(item: UnifiedInterviewItem): boolean {
  return item.type === 'voice' && getItemEvaluationPresentation(item).retryable;
}

function StatusIcon({ item }: { item: UnifiedInterviewItem }) {
  if (isEvaluateFailed(item)) return <AlertCircle className="w-4 h-4 text-red-500 dark:text-red-400"/>;
  if (isVoiceEvaluationRetryable(item)) {
    return <AlertCircle className="w-4 h-4 text-amber-500 dark:text-amber-400"/>;
  }
  if (isEvaluating(item)) return <RefreshCw className="w-4 h-4 text-blue-500 dark:text-blue-400 animate-spin"/>;
  if (isEvaluateCompleted(item)) return <CheckCircle className="w-4 h-4 text-green-500 dark:text-green-400"/>;
  if (item.status === 'IN_PROGRESS') return <PlayCircle className="w-4 h-4 text-blue-500 dark:text-blue-400"/>;
  return <Clock className="w-4 h-4 text-yellow-500 dark:text-yellow-400"/>;
}

function getStatusText(item: UnifiedInterviewItem): string {
  if (isEvaluateFailed(item)) return '评估失败';
  if (item.type === 'voice' && item.evaluateStatus) {
    return getItemEvaluationPresentation(item).label;
  }
  if (isEvaluating(item)) return item.evaluateStatus === 'PROCESSING' ? '评估中' : '等待评估';
  if (isEvaluateCompleted(item)) return '已完成';
  if (item.status === 'IN_PROGRESS') return '进行中';
  if (item.status === 'PAUSED') return '已暂停';
  if (isCompletedStatus(item.status)) return '已提交';
  return '已创建';
}

function formatDuration(seconds?: number): string {
  if (!seconds) return '-';
  const mins = Math.floor(seconds / 60);
  const secs = seconds % 60;
  return `${mins}分${secs}秒`;
}

function StatCard({
  icon: Icon,
  label,
  value,
  suffix,
  color,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: number | string;
  suffix?: string;
  color: string;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      className="bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border border-slate-100 dark:border-slate-700"
    >
      <div className="flex items-center gap-4">
        <div className={`p-3 rounded-lg ${color}`}>
          <Icon className="w-6 h-6 text-white" />
        </div>
        <div>
          <p className="text-sm text-slate-500 dark:text-slate-400">{label}</p>
          <p className="text-2xl font-bold text-slate-800 dark:text-white">
            {value}{suffix && <span className="text-base font-normal text-slate-400 dark:text-slate-500 ml-1">{suffix}</span>}
          </p>
        </div>
      </div>
    </motion.div>
  );
}

function TypeBadge({ item }: { item: UnifiedInterviewItem }) {
  if (item.sourceType === 'KNOWLEDGE_BASE') {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400 rounded-full text-xs font-medium">
        <FileText className="w-3 h-3" />
        知识库
      </span>
    );
  }
  if (item.type === 'voice') {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400 rounded-full text-xs font-medium">
        <Mic className="w-3 h-3" />
        语音
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400 rounded-full text-xs font-medium">
      <FileText className="w-3 h-3" />
      文字
    </span>
  );
}

interface InterviewHistoryPageProps {
  onBack: () => void;
  onViewInterview: (sessionId: string, resumeId?: number) => void;
  onRestartInterview?: (resumeId: number) => void;
  onContinueInterview?: (sessionId: string) => void;
  knowledgeBaseId?: number;
}

/** Shallow comparison for polling change-detection */
function itemsEqual(a: UnifiedInterviewItem[], b: UnifiedInterviewItem[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) {
    const ai = a[i], bi = b[i];
    if (ai.id !== bi.id || ai.status !== bi.status ||
        ai.evaluateStatus !== bi.evaluateStatus
        || ai.evaluateStatusUpdatedAt !== bi.evaluateStatusUpdatedAt
        || ai.overallScore !== bi.overallScore) return false;
  }
  return true;
}

export default function InterviewHistoryPage({
  onBack: _onBack,
  onViewInterview,
  onRestartInterview,
  onContinueInterview,
  knowledgeBaseId,
}: InterviewHistoryPageProps) {
  const navigate = useNavigate();
  const knowledgeBaseFilterId = knowledgeBaseId ?? null;
  const isKnowledgeBaseView = knowledgeBaseFilterId !== null && !Number.isNaN(knowledgeBaseFilterId);
  const [items, setItems] = useState<UnifiedInterviewItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [typeFilter, setTypeFilter] = useState<InterviewType>('all');
  const [categoryFilter, setCategoryFilter] = useState<string>('all');
  const [timeFilter, setTimeFilter] = useState<TimeRange>('all');
  const [completionFilter, setCompletionFilter] = useState<CompletionFilter>('all');
  const [deletingSessionId, setDeletingSessionId] = useState<string | null>(null);
  const [deleteItem, setDeleteItem] = useState<UnifiedInterviewItem | null>(null);
  const [exporting, setExporting] = useState<string | null>(null);
  const [retryingVoiceSessionId, setRetryingVoiceSessionId] = useState<number | null>(null);
  const [renamingSessionId, setRenamingSessionId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [savingRename, setSavingRename] = useState(false);
  const pollingRef = useRef<number | null>(null);
  const skillsRef = useRef<SkillDTO[]>([]);
  const skillsLoadedRef = useRef(false);

  const hasActiveKbFilters = categoryFilter !== 'all' || timeFilter !== 'all' || completionFilter !== 'all';

  const resetKbFilters = () => {
    setCategoryFilter('all');
    setTimeFilter('all');
    setCompletionFilter('all');
  };

  const loadAll = useCallback(async (isPolling = false) => {
    if (!isPolling) setLoading(true);

    try {
      // Only fetch skills on first load; reuse cached ref on polling
      if (!skillsLoadedRef.current) {
        skillsRef.current = await skillApi.listSkills().catch(() => [] as SkillDTO[]);
        skillsLoadedRef.current = true;
      }
      const loadedSkills = skillsRef.current;
      const textInterviews = await loadTextInterviews(loadedSkills);
      const scopedTextInterviews = isKnowledgeBaseView
        ? textInterviews.filter(item => item.knowledgeBaseId === knowledgeBaseFilterId)
        : textInterviews.filter(item => item.sourceType !== 'KNOWLEDGE_BASE');
      const voiceSessions = isKnowledgeBaseView ? [] : await loadVoiceInterviews();

      const voiceWithNames = voiceSessions.map(item => {
        const skillName = getTemplateName(item.title, loadedSkills);
        return skillName !== item.title ? { ...item, title: skillName } : item;
      });

      const all = [...scopedTextInterviews, ...voiceWithNames];
      all.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

      setItems(prev => {
        const hasActiveEvaluation = all.some(item =>
          shouldRefreshVoiceEvaluationPresentation(item.evaluateStatus));
        if (isPolling && itemsEqual(prev, all) && !hasActiveEvaluation) return prev;
        return all;
      });
    } catch (err) {
      console.error('加载面试记录失败', err);
    } finally {
      if (!isPolling) setLoading(false);
    }
  }, [isKnowledgeBaseView, knowledgeBaseFilterId]);

  // Load text interviews from dedicated API
  async function loadTextInterviews(skills: SkillDTO[]): Promise<UnifiedInterviewItem[]> {
    try {
      const sessions = await interviewApi.listSessions();
      return sessions.map((session: TextSessionMeta) => ({
        id: session.sessionId,
        type: 'text' as const,
        sourceType: session.sourceType,
        title: session.title?.trim() || (session.sourceType === 'KNOWLEDGE_BASE'
          ? `${getTemplateName(session.skillId, skills)} · 知识库面试`
          : getTemplateName(session.skillId, skills)),
        sessionId: session.sessionId,
        status: session.status,
        evaluateStatus: session.evaluateStatus ?? undefined,
        evaluateError: session.evaluateError ?? undefined,
        overallScore: session.overallScore,
        totalQuestions: session.totalQuestions,
        createdAt: session.createdAt,
        resumeId: session.resumeId ?? undefined,
        knowledgeBaseId: session.knowledgeBaseId ?? undefined,
        interviewCategory: session.interviewCategory ?? null,
      }));
    } catch {
      return [];
    }
  }

  // Load voice interviews from voice API
  async function loadVoiceInterviews(): Promise<UnifiedInterviewItem[]> {
    try {
      const sessions = await voiceInterviewApi.getAllSessions();
      return sessions.map((session: SessionMeta) => ({
        id: `voice-${session.sessionId}`,
        type: 'voice' as const,
        title: session.roleType,
        sessionId: String(session.sessionId),
        status: session.status,
        evaluateStatus: session.evaluateStatus,
        evaluateError: session.evaluateError,
        evaluateStatusUpdatedAt: session.updatedAt,
        overallScore: null,
        actualDuration: session.actualDuration,
        createdAt: session.createdAt,
        voiceSessionId: session.sessionId,
      }));
    } catch {
      return [];
    }
  }

  useEffect(() => {
    loadAll();
  }, [loadAll]);

  // Polling for evaluation status
  useEffect(() => {
    const hasEvaluating = items.some(i => isEvaluating(i));

    if (hasEvaluating && !pollingRef.current) {
      pollingRef.current = window.setInterval(() => loadAll(true), 3000);
    } else if (!hasEvaluating && pollingRef.current) {
      clearInterval(pollingRef.current);
      pollingRef.current = null;
    }

    return () => {
      if (pollingRef.current) {
        clearInterval(pollingRef.current);
        pollingRef.current = null;
      }
    };
  }, [items, loadAll]);

  const handleRowClick = (item: UnifiedInterviewItem) => {
    if (item.type === 'text') {
      onViewInterview(item.sessionId, item.resumeId);
    } else if (item.voiceSessionId) {
      const isLive = isLiveStatus(item.status);
      if (isLive) {
        navigate('/voice-interview', { state: { voiceSessionId: item.voiceSessionId } });
      } else {
        navigate(`/voice-interview/${item.voiceSessionId}/evaluation`);
      }
    }
  };

  const handleRenameStart = (item: UnifiedInterviewItem, event: React.MouseEvent) => {
    event.stopPropagation();
    if (item.type !== 'text') return;
    setRenamingSessionId(item.sessionId);
    setRenameValue(item.title);
  };

  const handleRenameCancel = (event?: React.MouseEvent) => {
    event?.stopPropagation();
    if (savingRename) return;
    setRenamingSessionId(null);
    setRenameValue('');
  };

  const handleRenameSave = async (item: UnifiedInterviewItem, event?: React.MouseEvent) => {
    event?.stopPropagation();
    const title = renameValue.trim();
    if (!title) {
      alert('面试记录名称不能为空');
      return;
    }
    if (title.length > 120) {
      alert('面试记录名称不能超过120个字符');
      return;
    }
    if (title === item.title) {
      handleRenameCancel();
      return;
    }

    setSavingRename(true);
    try {
      await historyApi.renameInterview(item.sessionId, title);
      setItems(current => current.map(currentItem => currentItem.sessionId === item.sessionId
        ? { ...currentItem, title }
        : currentItem));
      setRenamingSessionId(null);
      setRenameValue('');
    } catch (error) {
      alert(error instanceof Error ? error.message : '修改名称失败，请稍后重试');
    } finally {
      setSavingRename(false);
    }
  };

  const handleDeleteClick = (item: UnifiedInterviewItem, e: React.MouseEvent) => {
    e.stopPropagation();
    setDeleteItem(item);
  };

  const handleDeleteConfirm = async () => {
    if (!deleteItem) return;
    setDeletingSessionId(deleteItem.sessionId);
    try {
      if (deleteItem.type === 'voice' && deleteItem.voiceSessionId) {
        await voiceInterviewApi.deleteSession(deleteItem.voiceSessionId);
      } else {
        await historyApi.deleteInterview(deleteItem.sessionId);
      }
      await loadAll();
      setDeleteItem(null);
    } catch (err) {
      alert(err instanceof Error ? err.message : '删除失败，请稍后重试');
    } finally {
      setDeletingSessionId(null);
    }
  };

  const handleExport = async (sessionId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setExporting(sessionId);
    try {
      const blob = await historyApi.exportInterviewPdf(sessionId);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `面试报告_${sessionId.slice(-8)}.pdf`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(url);
    } catch {
      alert('导出失败，请重试');
    } finally {
      setExporting(null);
    }
  };

  const handleRetryVoiceEvaluation = async (
    item: UnifiedInterviewItem,
    e: React.MouseEvent,
  ) => {
    e.stopPropagation();
    if (!item.voiceSessionId) return;

    setRetryingVoiceSessionId(item.voiceSessionId);
    try {
      await voiceInterviewApi.generateEvaluation(item.voiceSessionId);
      await loadAll(true);
    } catch {
      alert('重新生成评估失败，请稍后再试');
    } finally {
      setRetryingVoiceSessionId(null);
    }
  };

  // Filter + search（知识库视图额外支持方向 / 时间范围 / 完成状态筛选，条件之间为 AND 关系）
  const filtered = useMemo(() => items.filter(item => {
    if (typeFilter !== 'all' && item.type !== typeFilter) return false;
    if (searchTerm && !item.title.toLowerCase().includes(searchTerm.toLowerCase())) return false;
    if (isKnowledgeBaseView) {
      if (categoryFilter === ALL_DIRECTIONS_VALUE && item.interviewCategory) return false;
      if (categoryFilter !== 'all'
          && categoryFilter !== ALL_DIRECTIONS_VALUE
          && item.interviewCategory !== categoryFilter) return false;
      if (timeFilter !== 'all') {
        const days = timeFilter === '7d' ? 7 : timeFilter === '30d' ? 30 : 90;
        const cutoff = Date.now() - days * 24 * 60 * 60 * 1000;
        if (new Date(item.createdAt).getTime() < cutoff) return false;
      }
      if (completionFilter !== 'all') {
        const completed = isEvaluateCompleted(item);
        if (completionFilter === 'completed' && !completed) return false;
        if (completionFilter === 'inProgress' && completed) return false;
      }
    }
    return true;
  }), [items, typeFilter, searchTerm, isKnowledgeBaseView, categoryFilter, timeFilter, completionFilter]);

  const categoryOptions = useMemo(() => {
    const values = new Set<string>();
    let hasUncategorized = false;
    items.forEach(item => {
      if (item.interviewCategory) {
        values.add(item.interviewCategory);
      } else {
        hasUncategorized = true;
      }
    });
    return {
      values: Array.from(values).sort((a, b) => a.localeCompare(b, 'zh-CN')),
      hasUncategorized,
    };
  }, [items]);

  // 统计数据基于筛选后的记录，保证与列表一致
  const stats = useMemo(
    () => calculateInterviewStats(items, filtered, isKnowledgeBaseView),
    [items, filtered, isKnowledgeBaseView],
  );

  const trendData = useMemo(() => {
    return filtered
      .filter(item => isEvaluateCompleted(item) && item.overallScore !== null)
      .map(item => ({
        name: formatDateOnly(item.createdAt),
        score: item.overallScore || 0,
      }))
      .reverse();
  }, [filtered]);

  // 筛选无结果（仅知识库视图且存在生效筛选条件时）与原始空状态区分开
  const showFilterEmpty = isKnowledgeBaseView && hasActiveKbFilters && items.length > 0 && filtered.length === 0;
  const showOriginalEmpty = filtered.length === 0 && !showFilterEmpty;

  return (
    <motion.div className="w-full" initial={{ opacity: 0 }} animate={{ opacity: 1 }}>
      {/* Header */}
      <div className="flex justify-between items-start mb-8 flex-wrap gap-6">
        <div>
          <motion.h1
            className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3"
            initial={{ opacity: 0, x: -20 }}
            animate={{ opacity: 1, x: 0 }}
          >
            <Users className="w-7 h-7 text-primary-500" />
            {isKnowledgeBaseView ? '知识库面试记录' : '面试记录'}
          </motion.h1>
          <motion.p
            className="text-slate-500 dark:text-slate-400 mt-1"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.1 }}
          >
            {isKnowledgeBaseView ? '查看当前知识库的面试记录和表现趋势' : '查看和管理所有模拟面试记录'}
          </motion.p>
        </div>

        <motion.div
          className="flex items-center gap-3 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-600 rounded-xl px-4 py-2.5 min-w-[280px] focus-within:border-primary-500 focus-within:ring-2 focus-within:ring-primary-100 dark:focus-within:ring-primary-900/30 transition-all"
          initial={{ opacity: 0, x: 20 }}
          animate={{ opacity: 1, x: 0 }}
        >
          <Search className="w-5 h-5 text-slate-400" />
          <input
            type="text"
            placeholder="搜索名称..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            className="flex-1 outline-none text-slate-700 dark:text-slate-200 placeholder:text-slate-400 bg-transparent"
          />
        </motion.div>
      </div>

      {/* Stats */}
      {!loading && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
          <StatCard icon={Users} label="面试总数" value={stats.totalCount} color="bg-primary-500" />
          <StatCard icon={CheckCircle} label="已完成" value={stats.completedCount} color="bg-emerald-500" />
          <StatCard icon={TrendingUp} label="平均分数" value={stats.averageScore} suffix="分" color="bg-indigo-500" />
        </div>
      )}

      {isKnowledgeBaseView && trendData.length > 0 && (
        <motion.div
          className="bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border border-slate-100 dark:border-slate-700 mb-8"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
        >
          <div className="flex items-center justify-between mb-6">
            <div className="flex items-center gap-2">
              <TrendingUp className="w-5 h-5 text-primary-500" />
              <span className="font-semibold text-slate-800 dark:text-white">面试表现趋势</span>
            </div>
            <span className="text-sm text-slate-500 dark:text-slate-400">共 {trendData.length} 场练习</span>
          </div>
          <div className="h-48">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={trendData} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" className="dark:stroke-slate-700" />
                <XAxis dataKey="name" axisLine={false} tickLine={false} tick={{ fill: '#94a3b8', fontSize: 12 }} />
                <YAxis domain={[0, 100]} axisLine={false} tickLine={false} tick={{ fill: '#94a3b8', fontSize: 12 }} />
                <Tooltip formatter={(value) => [`${value} 分`, '得分']} />
                <Line
                  type="monotone"
                  dataKey="score"
                  stroke="#6366f1"
                  strokeWidth={3}
                  dot={{ fill: '#6366f1', strokeWidth: 2, r: 5 }}
                  activeDot={{ r: 8, fill: '#6366f1' }}
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </motion.div>
      )}

      {/* 知识库面试记录筛选（仅知识库视图显示） */}
      {isKnowledgeBaseView && (
        <motion.div
          className="flex items-center gap-3 flex-wrap bg-white dark:bg-slate-800 border border-slate-100 dark:border-slate-700 rounded-xl px-4 py-3 mb-6 shadow-sm"
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
        >
          <Filter className="w-4 h-4 text-slate-400 shrink-0" />
          <select
            value={categoryFilter}
            onChange={(e) => setCategoryFilter(e.target.value)}
            aria-label="按面试方向筛选"
            className="bg-slate-50 dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-lg px-3 py-2 text-sm text-slate-700 dark:text-slate-200 outline-none focus:border-primary-500 transition-colors cursor-pointer"
          >
            <option value="all">全部记录</option>
            {categoryOptions.values.map(category => (
              <option key={category} value={category}>{category}</option>
            ))}
            {categoryOptions.hasUncategorized && (
              <option value={ALL_DIRECTIONS_VALUE}>{ALL_DIRECTIONS_LABEL}</option>
            )}
          </select>
          <select
            value={timeFilter}
            onChange={(e) => setTimeFilter(e.target.value as TimeRange)}
            aria-label="按时间范围筛选"
            className="bg-slate-50 dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-lg px-3 py-2 text-sm text-slate-700 dark:text-slate-200 outline-none focus:border-primary-500 transition-colors cursor-pointer"
          >
            {TIME_RANGE_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
          <select
            value={completionFilter}
            onChange={(e) => setCompletionFilter(e.target.value as CompletionFilter)}
            aria-label="按完成状态筛选"
            className="bg-slate-50 dark:bg-slate-700 border border-slate-200 dark:border-slate-600 rounded-lg px-3 py-2 text-sm text-slate-700 dark:text-slate-200 outline-none focus:border-primary-500 transition-colors cursor-pointer"
          >
            {COMPLETION_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
          {hasActiveKbFilters && (
            <button
              onClick={resetKbFilters}
              className="inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-primary-500 dark:hover:text-primary-400 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg transition-colors"
            >
              <RotateCcw className="w-3.5 h-3.5" />
              重置筛选
            </button>
          )}
        </motion.div>
      )}

      {/* Type filter tabs */}
      {!isKnowledgeBaseView && <div className="flex items-center gap-2 mb-6">
        {([
          { key: 'all', label: '全部' },
          { key: 'text', label: '文字面试' },
          { key: 'voice', label: '语音面试' },
        ] as const).map(tab => (
          <button
            key={tab.key}
            onClick={() => setTypeFilter(tab.key)}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition-colors ${
              typeFilter === tab.key
                ? 'bg-primary-500 text-white'
                : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 border border-slate-200 dark:border-slate-600'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>}

      {/* Loading */}
      {loading && (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
        </div>
      )}

      {/* 筛选无结果（知识库视图且筛选条件生效） */}
      {!loading && showFilterEmpty && (
        <motion.div
          className="text-center py-20 bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700"
          initial={{ opacity: 0, scale: 0.95 }}
          animate={{ opacity: 1, scale: 1 }}
        >
          <Search className="w-16 h-16 text-slate-300 dark:text-slate-600 mx-auto mb-4" />
          <h3 className="text-xl font-semibold text-slate-700 dark:text-slate-300 mb-2">没有符合当前筛选条件的面试记录</h3>
          <p className="text-slate-500 dark:text-slate-400 mb-6">可以尝试调整面试方向、时间范围或完成状态</p>
          <button
            onClick={resetKbFilters}
            className="inline-flex items-center gap-2 px-5 py-2.5 bg-primary-500 hover:bg-primary-600 text-white text-sm font-medium rounded-lg transition-colors"
          >
            <RotateCcw className="w-4 h-4" />
            重置筛选
          </button>
        </motion.div>
      )}

      {/* Empty */}
      {!loading && showOriginalEmpty && (
        <motion.div
          className="text-center py-20 bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700"
          initial={{ opacity: 0, scale: 0.95 }}
          animate={{ opacity: 1, scale: 1 }}
        >
          <Users className="w-16 h-16 text-slate-300 dark:text-slate-600 mx-auto mb-4" />
          <h3 className="text-xl font-semibold text-slate-700 dark:text-slate-300 mb-2">暂无面试记录</h3>
          <p className="text-slate-500 dark:text-slate-400">
            {isKnowledgeBaseView ? '开始一次知识库面试后，记录将显示在这里' : '开始一次模拟面试后，记录将显示在这里'}
          </p>
        </motion.div>
      )}

      {/* Table */}
      {!loading && filtered.length > 0 && (
        <motion.div
          className="bg-white dark:bg-slate-800 rounded-xl shadow-sm border border-slate-100 dark:border-slate-700 overflow-hidden"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2 }}
        >
          <table className="w-full">
            <thead className="bg-slate-50 dark:bg-slate-700/50 border-b border-slate-100 dark:border-slate-600">
              <tr>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">类型</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">名称</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">状态</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">得分</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">详情</th>
                <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">时间</th>
                <th className="text-right px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">操作</th>
              </tr>
            </thead>
            <tbody>
              <AnimatePresence>
                {filtered.map((item, index) => (
                  <motion.tr
                    key={item.id}
                    initial={{ opacity: 0, y: 10 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ delay: index * 0.05 }}
                    onClick={() => handleRowClick(item)}
                    className="border-b border-slate-50 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer transition-colors group"
                  >
                    <td className="px-6 py-4">
                      <TypeBadge item={item} />
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-3">
                        {item.type === 'text' ? (
                          <FileText className="w-5 h-5 text-slate-400" />
                        ) : (
                          <Mic className="w-5 h-5 text-purple-400" />
                        )}
                        <div>
                          <div className="flex items-center gap-2 flex-wrap">
                            {renamingSessionId === item.sessionId && item.type === 'text' ? (
                              <div className="flex items-center gap-1" onClick={(event) => event.stopPropagation()}>
                                <input
                                  autoFocus
                                  value={renameValue}
                                  maxLength={120}
                                  onChange={(event) => setRenameValue(event.target.value)}
                                  onKeyDown={(event) => {
                                    if (event.key === 'Enter') void handleRenameSave(item);
                                    if (event.key === 'Escape') handleRenameCancel();
                                  }}
                                  disabled={savingRename}
                                  className="w-48 px-2 py-1 rounded-lg border border-primary-300 dark:border-primary-600 bg-white dark:bg-slate-800 text-sm text-slate-800 dark:text-white outline-none focus:ring-2 focus:ring-primary-500/20"
                                  aria-label="面试记录名称"
                                />
                                <button
                                  type="button"
                                  onClick={(event) => void handleRenameSave(item, event)}
                                  disabled={savingRename}
                                  className="p-1 text-emerald-500 hover:bg-emerald-50 dark:hover:bg-emerald-900/30 rounded disabled:opacity-50"
                                  title="保存名称"
                                >
                                  {savingRename ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle className="w-4 h-4" />}
                                </button>
                                <button
                                  type="button"
                                  onClick={handleRenameCancel}
                                  disabled={savingRename}
                                  className="p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 rounded disabled:opacity-50"
                                  title="取消修改"
                                >
                                  <XCircle className="w-4 h-4" />
                                </button>
                              </div>
                            ) : (
                              <>
                                <p className="font-medium text-slate-800 dark:text-white">{item.title}</p>
                                {item.type === 'text' && (
                                  <button
                                    type="button"
                                    onClick={(event) => handleRenameStart(item, event)}
                                    className="p-1 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded transition-colors"
                                    title="修改面试记录名称"
                                  >
                                    <Pencil className="w-3.5 h-3.5" />
                                  </button>
                                )}
                              </>
                            )}
                            {isKnowledgeBaseView && item.type === 'text' && (
                              <span
                                className="inline-flex items-center gap-1 px-1.5 py-0.5 bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600 dark:text-indigo-400 rounded text-xs font-medium"
                                title="面试方向"
                              >
                                <Tag className="w-3 h-3" />
                                {getKnowledgeBaseInterviewCategoryLabel(item.interviewCategory)}
                              </span>
                            )}
                          </div>
                          <p className="text-xs text-slate-400 dark:text-slate-500">#{item.id.slice(-8)}</p>
                        </div>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-2">
                        <StatusIcon item={item} />
                        <span className="text-sm text-slate-600 dark:text-slate-300">{getStatusText(item)}</span>
                      </div>
                    </td>
                    <td className="px-6 py-4">
                      {isEvaluateCompleted(item) && item.overallScore !== null ? (
                        <div className="flex items-center gap-3">
                          <div className="w-16 h-2 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                            <motion.div
                              className={`h-full ${getScoreProgressColor(item.overallScore)} rounded-full`}
                              initial={{ width: 0 }}
                              animate={{ width: `${item.overallScore}%` }}
                              transition={{ duration: 0.8, delay: index * 0.05 }}
                            />
                          </div>
                          <span className="font-bold text-slate-800 dark:text-white">{item.overallScore}</span>
                        </div>
                      ) : isVoiceEvaluationRetryable(item) ? (
                        <span className="text-amber-600 dark:text-amber-400 text-sm">可重新生成</span>
                      ) : isEvaluating(item) ? (
                        <span className="text-blue-500 dark:text-blue-400 text-sm">生成中...</span>
                      ) : isEvaluateFailed(item) ? (
                        <span className="text-red-500 dark:text-red-400 text-sm">失败</span>
                      ) : (
                        <span className="text-slate-400 dark:text-slate-500">-</span>
                      )}
                    </td>
                    <td className="px-6 py-4">
                      {item.type === 'text' && item.totalQuestions != null ? (
                        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded-lg text-sm">
                          {item.totalQuestions} 题
                        </span>
                      ) : item.type === 'voice' ? (
                        <span className="text-sm text-slate-500 dark:text-slate-400">
                          {formatDuration(item.actualDuration)}
                        </span>
                      ) : (
                        <span className="text-slate-400">-</span>
                      )}
                    </td>
                    <td className="px-6 py-4 text-sm text-slate-500 dark:text-slate-400">
                      {formatDate(item.createdAt)}
                    </td>
                    <td className="px-6 py-4 text-right">
                      <div className="flex items-center justify-end gap-1">
                        {item.type === 'text' && !isCompletedStatus(item.status) && !isEvaluateCompleted(item) && onContinueInterview && (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              if (item.sourceType === 'KNOWLEDGE_BASE') {
                                navigate(`/knowledgebase-interview/${item.sessionId}`, {
                                  state: { knowledgeBaseId: item.knowledgeBaseId },
                                });
                              } else {
                                onContinueInterview(item.sessionId);
                              }
                            }}
                            className="p-2 text-slate-400 hover:text-blue-500 hover:bg-blue-50 dark:hover:bg-blue-900/30 rounded-lg transition-colors"
                            title="继续面试"
                          >
                            <PlayCircle className="w-4 h-4" />
                          </button>
                        )}
                        {item.type === 'voice' && isLiveStatus(item.status) && item.voiceSessionId && (
                          <button
                            onClick={(e) => { e.stopPropagation(); navigate('/voice-interview', { state: { voiceSessionId: item.voiceSessionId } }); }}
                            className="p-2 text-slate-400 hover:text-blue-500 hover:bg-blue-50 dark:hover:bg-blue-900/30 rounded-lg transition-colors"
                            title="继续面试"
                          >
                            <PlayCircle className="w-4 h-4" />
                          </button>
                        )}
                        {isEvaluateCompleted(item) && item.type === 'text' && (
                          <button
                            onClick={(e) => handleExport(item.sessionId, e)}
                            disabled={exporting === item.sessionId}
                            className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors disabled:opacity-50"
                            title="导出PDF"
                          >
                            {exporting === item.sessionId ? (
                              <Loader2 className="w-4 h-4 animate-spin" />
                            ) : (
                              <Download className="w-4 h-4" />
                            )}
                          </button>
                        )}
                        {isEvaluateCompleted(item) && item.type === 'text' && item.resumeId && onRestartInterview && (
                          <button
                            onClick={(e) => { e.stopPropagation(); onRestartInterview(item.resumeId!); }}
                            className="p-2 text-slate-400 hover:text-emerald-500 hover:bg-emerald-50 dark:hover:bg-emerald-900/30 rounded-lg transition-colors"
                            title="重新面试"
                          >
                            <RotateCcw className="w-4 h-4" />
                          </button>
                        )}
                        {isVoiceEvaluationRetryable(item) && item.voiceSessionId && (
                          <button
                            onClick={(e) => handleRetryVoiceEvaluation(item, e)}
                            disabled={retryingVoiceSessionId === item.voiceSessionId}
                            className="p-2 text-amber-500 hover:text-amber-600 hover:bg-amber-50 dark:hover:bg-amber-900/30 rounded-lg transition-colors disabled:opacity-50"
                            title="重新生成评估"
                          >
                            {retryingVoiceSessionId === item.voiceSessionId ? (
                              <Loader2 className="w-4 h-4 animate-spin" />
                            ) : (
                              <RefreshCw className="w-4 h-4" />
                            )}
                          </button>
                        )}
                        <button
                            onClick={(e) => handleDeleteClick(item, e)}
                            disabled={deletingSessionId === item.sessionId}
                            className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg transition-colors disabled:opacity-50"
                            title="删除"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        <ChevronRight className="w-5 h-5 text-slate-300 dark:text-slate-600 group-hover:text-primary-500 group-hover:translate-x-1 transition-all"/>
                      </div>
                    </td>
                  </motion.tr>
                ))}
              </AnimatePresence>
            </tbody>
          </table>
        </motion.div>
      )}

      <DeleteConfirmDialog
        open={deleteItem !== null}
        item={deleteItem ? { id: 0, sessionId: deleteItem.sessionId } : null}
        itemType="面试记录"
        loading={deletingSessionId !== null}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteItem(null)}
      />
    </motion.div>
  );
}
