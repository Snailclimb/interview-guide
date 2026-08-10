import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
  AlertCircle,
  ArrowRight,
  Briefcase,
  CheckCircle2,
  ChevronRight,
  ClipboardList,
  FileText,
  History,
  Loader2,
  Sparkles,
  Target,
  Trash2,
  XCircle,
} from 'lucide-react';
import { jdMatchApi } from '../api/jdMatch';
import { resumeApi } from '../api/resume';
import type { ResumeListItem } from '../types/resume';
import type { JdMatchDetail, JdMatchListItem, JdMatchScoreDetail, JdMatchStatus } from '../types/jdMatch';
import { formatDateTime } from '../utils/date';
import { getScoreProgressColor, getScoreTextColor } from '../utils/score';

// 五个匹配维度的配置
const DIMENSIONS: { key: keyof JdMatchScoreDetail; label: string; max: number; desc: string }[] = [
  { key: 'hardRequirementScore', label: '硬性要求', max: 25, desc: '学历 · 年限 · 证书' },
  { key: 'skillMatchScore', label: '技能栈匹配', max: 25, desc: '核心技术栈覆盖' },
  { key: 'experienceScore', label: '经验匹配', max: 20, desc: '行业 · 业务领域' },
  { key: 'projectScore', label: '项目经历', max: 20, desc: '项目契合度' },
  { key: 'softSkillScore', label: '软素质', max: 10, desc: '沟通 · 协作 · 自驱' },
];

function matchLevel(score: number): { label: string; color: string } {
  if (score >= 85) return { label: '高度匹配', color: 'text-emerald-500 dark:text-emerald-400' };
  if (score >= 70) return { label: '良好匹配', color: 'text-teal-500 dark:text-teal-400' };
  if (score >= 50) return { label: '中等匹配', color: 'text-amber-500 dark:text-amber-400' };
  return { label: '匹配度较低', color: 'text-red-500 dark:text-red-400' };
}

function statusConfig(status: JdMatchStatus): { label: string; icon: React.ComponentType<{ className?: string }>; className: string } {
  switch (status) {
    case 'PENDING':
      return { label: '等待分析', icon: ClockIcon, className: 'text-yellow-500 dark:text-yellow-400' };
    case 'PROCESSING':
      return { label: '分析中', icon: Loader2, className: 'text-blue-500 dark:text-blue-400' };
    case 'COMPLETED':
      return { label: '已完成', icon: CheckCircle2, className: 'text-emerald-500 dark:text-emerald-400' };
    case 'FAILED':
      return { label: '分析失败', icon: XCircle, className: 'text-red-500 dark:text-red-400' };
    default:
      return { label: '未知', icon: ClockIcon, className: 'text-slate-400' };
  }
}

function ClockIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="10" />
      <polyline points="12 6 12 12 16 14" />
    </svg>
  );
}

function StatusBadge({ status }: { status: JdMatchStatus }) {
  const cfg = statusConfig(status);
  const Icon = cfg.icon;
  return (
    <span className={`inline-flex items-center gap-1.5 text-xs font-medium ${cfg.className}`}>
      {status === 'PROCESSING' ? (
        <Loader2 className="w-3.5 h-3.5 animate-spin" />
      ) : (
        <Icon className="w-3.5 h-3.5" />
      )}
      {cfg.label}
    </span>
  );
}

function ScoreRing({ score }: { score: number }) {
  const radius = 56;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (score / 100) * circumference;
  const color = getScoreProgressColor(score);
  const textColor = getScoreTextColor(score);

  return (
    <div className="relative w-36 h-36">
      <svg className="w-36 h-36 -rotate-90" viewBox="0 0 128 128">
        <circle
          cx="64" cy="64" r={radius}
          className="fill-none stroke-slate-200 dark:stroke-slate-700"
          strokeWidth="10"
        />
        <circle
          cx="64" cy="64" r={radius}
          className={`fill-none ${color} transition-all duration-1000`}
          strokeWidth="10"
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className={`text-4xl font-bold ${textColor}`}>{score}</span>
        <span className="text-xs text-slate-400 dark:text-slate-500">匹配总分</span>
      </div>
    </div>
  );
}

function DimensionBar({ label, desc, score, max }: { label: string; desc: string; score: number; max: number }) {
  const percent = Math.min(100, Math.round((score / max) * 100));
  const color = getScoreProgressColor(score, [80, 60]);
  const textColor = getScoreTextColor(score, [80, 60]);

  return (
    <div>
      <div className="flex items-center justify-between mb-1.5">
        <div>
          <span className="text-sm font-medium text-slate-700 dark:text-slate-200">{label}</span>
          <span className="ml-2 text-xs text-slate-400 dark:text-slate-500">{desc}</span>
        </div>
        <span className={`text-sm font-semibold ${textColor}`}>
          {score}<span className="text-xs text-slate-400 font-normal">/{max}</span>
        </span>
      </div>
      <div className="h-2 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
        <div
          className={`h-full ${color} rounded-full transition-all duration-700`}
          style={{ width: `${percent}%` }}
        />
      </div>
    </div>
  );
}

function SectionCard({ icon, title, children }: { icon: React.ReactNode; title: string; children: React.ReactNode }) {
  return (
    <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl p-5">
      <div className="flex items-center gap-2 mb-4">
        <span className="w-7 h-7 rounded-lg bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400 flex items-center justify-center">
          {icon}
        </span>
        <h3 className="text-sm font-semibold text-slate-800 dark:text-white">{title}</h3>
      </div>
      {children}
    </div>
  );
}

export default function JdMatchPage() {
  const [resumes, setResumes] = useState<ResumeListItem[]>([]);
  const [resumesLoading, setResumesLoading] = useState(true);
  const [resumeId, setResumeId] = useState<number | ''>('');
  const [jdTitle, setJdTitle] = useState('');
  const [jdText, setJdText] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const [matches, setMatches] = useState<JdMatchListItem[]>([]);
  const [currentDetail, setCurrentDetail] = useState<JdMatchDetail | null>(null);
  const [viewingId, setViewingId] = useState<number | null>(null);
  const [historyLoading, setHistoryLoading] = useState(true);
  const pollTimerRef = useRef<number | null>(null);

  // 加载简历列表
  useEffect(() => {
    let mounted = true;
    resumeApi.getAllResumes()
      .then(data => {
        if (!mounted) return;
        setResumes(data);
        if (data.length > 0 && resumeId === '') {
          setResumeId(data[0].id);
        }
      })
      .catch(err => console.error('加载简历列表失败', err))
      .finally(() => mounted && setResumesLoading(false));
    return () => {
      mounted = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 加载历史记录
  const loadHistory = useCallback(async () => {
    try {
      const data = await jdMatchApi.getAllMatches();
      setMatches(data);
      // 有分析中的任务时自动查看最新一条
      const active = data.find(m => m.status === 'PENDING' || m.status === 'PROCESSING');
      if (active && !viewingId) {
        setViewingId(active.id);
        setCurrentDetail(await jdMatchApi.getMatchDetail(active.id));
        startPolling(active.id);
      }
    } catch (err) {
      console.error('加载匹配历史失败', err);
    } finally {
      setHistoryLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewingId]);

  useEffect(() => {
    loadHistory();
    return () => stopPolling();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 轮询分析状态
  const startPolling = useCallback((id: number) => {
    stopPolling();
    pollTimerRef.current = window.setInterval(async () => {
      try {
        const detail = await jdMatchApi.getMatchDetail(id);
        setCurrentDetail(detail);
        if (detail.status === 'COMPLETED' || detail.status === 'FAILED') {
          stopPolling();
          loadHistory();
        }
      } catch {
        // 忽略瞬时错误，继续轮询
      }
    }, 2000);
  }, [loadHistory]);

  const stopPolling = useCallback(() => {
    if (pollTimerRef.current !== null) {
      clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  }, []);

  useEffect(() => stopPolling, [stopPolling]);

  // 提交匹配分析
  const handleSubmit = async () => {
    setSubmitError(null);
    if (!resumeId) {
      setSubmitError('请先选择一份简历');
      return;
    }
    if (!jdText.trim()) {
      setSubmitError('请输入岗位 JD 内容');
      return;
    }
    setSubmitting(true);
    try {
      const created = await jdMatchApi.createMatch({
        resumeId: Number(resumeId),
        jdTitle: jdTitle.trim() || undefined,
        jdText: jdText.trim(),
      });
      setViewingId(created.id);
      setCurrentDetail(await jdMatchApi.getMatchDetail(created.id));
      startPolling(created.id);
      loadHistory();
    } catch (err) {
      console.error('创建匹配分析失败', err);
      setSubmitError(err instanceof Error ? err.message : '创建匹配分析失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  // 查看历史详情
  const handleViewDetail = async (id: number) => {
    stopPolling();
    setViewingId(id);
    try {
      const detail = await jdMatchApi.getMatchDetail(id);
      setCurrentDetail(detail);
      if (detail.status === 'PENDING' || detail.status === 'PROCESSING') {
        startPolling(id);
      }
    } catch (err) {
      console.error('加载匹配详情失败', err);
    }
  };

  // 删除历史记录
  const handleDelete = async (id: number, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!window.confirm('确定删除这条匹配分析记录吗？')) return;
    try {
      await jdMatchApi.deleteMatch(id);
      if (viewingId === id) {
        setViewingId(null);
        setCurrentDetail(null);
      }
      loadHistory();
    } catch (err) {
      console.error('删除匹配记录失败', err);
    }
  };

  const isAnalyzing = currentDetail?.status === 'PENDING' || currentDetail?.status === 'PROCESSING';
  const detail = currentDetail;
  const level = detail?.overallScore != null ? matchLevel(detail.overallScore) : null;

  return (
    <div className="max-w-7xl mx-auto">
      {/* 头部 */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-2">
          <Target className="w-6 h-6 text-primary-500" />
          JD 匹配分析
        </h1>
        <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
          将你的简历与目标岗位 JD 进行 AI 匹配度检测，查看差距并获取针对性优化建议
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
        {/* 左栏：表单 */}
        <div className="lg:col-span-2 space-y-6">
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl p-6">
            <h2 className="text-base font-semibold text-slate-800 dark:text-white mb-4 flex items-center gap-2">
              <Briefcase className="w-4 h-4 text-primary-500" />
              发起匹配分析
            </h2>

            {/* 选择简历 */}
            <div className="mb-4">
              <label className="block text-sm font-medium text-slate-600 dark:text-slate-300 mb-1.5">
                选择简历 <span className="text-red-500">*</span>
              </label>
              {resumesLoading ? (
                <div className="flex items-center gap-2 text-sm text-slate-400 py-2">
                  <Loader2 className="w-4 h-4 animate-spin" /> 加载简历列表...
                </div>
              ) : resumes.length === 0 ? (
                <div className="text-sm text-amber-600 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-lg p-3">
                  还没有简历，请先
                  <Link to="/upload" className="underline font-medium mx-1">上传简历</Link>
                  再进行匹配分析。
                </div>
              ) : (
                <select
                  value={resumeId}
                  onChange={e => setResumeId(e.target.value ? Number(e.target.value) : '')}
                  className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 px-3 py-2 text-sm text-slate-700 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-primary-500"
                >
                  <option value="">请选择简历</option>
                  {resumes.map(r => (
                    <option key={r.id} value={r.id}>
                      {r.filename}
                    </option>
                  ))}
                </select>
              )}
            </div>

            {/* JD 标题 */}
            <div className="mb-4">
              <label className="block text-sm font-medium text-slate-600 dark:text-slate-300 mb-1.5">
                岗位名称 <span className="text-xs text-slate-400 font-normal">（可选）</span>
              </label>
              <input
                type="text"
                value={jdTitle}
                onChange={e => setJdTitle(e.target.value)}
                placeholder="例如：高级 Java 开发工程师"
                className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 px-3 py-2 text-sm text-slate-700 dark:text-slate-200 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-primary-500"
              />
            </div>

            {/* JD 文本 */}
            <div className="mb-4">
              <label className="block text-sm font-medium text-slate-600 dark:text-slate-300 mb-1.5">
                岗位 JD 内容 <span className="text-red-500">*</span>
              </label>
              <textarea
                value={jdText}
                onChange={e => setJdText(e.target.value)}
                rows={10}
                placeholder={'将职位描述粘贴到这里，例如：\n\n岗位职责：\n1. 负责核心业务系统设计与开发...\n\n任职要求：\n1. 本科及以上学历，3年以上Java开发经验...\n2. 熟悉Spring Boot、MySQL、Redis...'}
                className="w-full rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 px-3 py-2 text-sm text-slate-700 dark:text-slate-200 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-primary-500 resize-y min-h-[200px]"
              />
            </div>

            {submitError && (
              <div className="mb-4 flex items-start gap-2 text-sm text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg p-3">
                <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
                <span>{submitError}</span>
              </div>
            )}

            <button
              onClick={handleSubmit}
              disabled={submitting || resumes.length === 0}
              className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg bg-primary-500 text-white text-sm font-medium hover:bg-primary-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {submitting ? (
                <>
                  <Loader2 className="w-4 h-4 animate-spin" /> 正在创建任务...
                </>
              ) : (
                <>
                  <Sparkles className="w-4 h-4" /> 开始匹配分析
                </>
              )}
            </button>
          </div>

          {/* 历史记录 */}
          <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl p-6">
            <h2 className="text-base font-semibold text-slate-800 dark:text-white mb-4 flex items-center gap-2">
              <History className="w-4 h-4 text-primary-500" />
              历史记录
            </h2>
            {historyLoading ? (
              <div className="flex items-center gap-2 text-sm text-slate-400 py-3">
                <Loader2 className="w-4 h-4 animate-spin" /> 加载历史记录...
              </div>
            ) : matches.length === 0 ? (
              <div className="text-sm text-slate-400 dark:text-slate-500 py-3 text-center">
                暂无匹配分析记录
              </div>
            ) : (
              <div className="space-y-2">
                {matches.map(m => (
                  <div
                    key={m.id}
                    onClick={() => handleViewDetail(m.id)}
                    className={`group flex items-center gap-3 p-3 rounded-lg border cursor-pointer transition-colors ${
                      viewingId === m.id
                        ? 'border-primary-300 dark:border-primary-700 bg-primary-50 dark:bg-primary-900/20'
                        : 'border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800'
                    }`}
                  >
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium text-slate-700 dark:text-slate-200 truncate">
                          {m.jdTitle || '未命名岗位'}
                        </span>
                        <StatusBadge status={m.status} />
                      </div>
                      <div className="text-xs text-slate-400 dark:text-slate-500 mt-0.5 truncate">
                        {m.resumeFilename} · {formatDateTime(m.createdAt)}
                      </div>
                    </div>
                    {m.overallScore != null && (
                      <span className={`text-lg font-bold ${getScoreTextColor(m.overallScore)}`}>
                        {m.overallScore}
                      </span>
                    )}
                    <ChevronRight className="w-4 h-4 text-slate-300 dark:text-slate-600 group-hover:text-slate-400" />
                    <button
                      onClick={e => handleDelete(m.id, e)}
                      className="p-1 rounded text-slate-300 dark:text-slate-600 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
                      title="删除记录"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>

        {/* 右栏：结果 */}
        <div className="lg:col-span-3 space-y-6">
          {!detail ? (
            <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl p-12 text-center">
              <div className="w-16 h-16 mx-auto mb-4 rounded-2xl bg-slate-100 dark:bg-slate-800 flex items-center justify-center">
                <ClipboardList className="w-8 h-8 text-slate-400 dark:text-slate-500" />
              </div>
              <h3 className="text-base font-semibold text-slate-700 dark:text-slate-200">
                还没有匹配结果
              </h3>
              <p className="text-sm text-slate-400 dark:text-slate-500 mt-2 max-w-md mx-auto">
                在左侧选择简历并粘贴目标岗位 JD，点击"开始匹配分析"，AI 将为你输出匹配总分、维度评分、差距分析和简历优化建议。
              </p>
            </div>
          ) : detail.status === 'FAILED' ? (
            <div className="bg-white dark:bg-slate-900 border border-red-200 dark:border-red-800 rounded-xl p-12 text-center">
              <XCircle className="w-12 h-12 text-red-500 mx-auto mb-4" />
              <h3 className="text-base font-semibold text-red-600 dark:text-red-400">
                匹配分析失败
              </h3>
              <p className="text-sm text-slate-500 dark:text-slate-400 mt-2">
                {detail.error || 'AI 服务暂时不可用，请稍后重试'}
              </p>
            </div>
          ) : isAnalyzing ? (
            <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl p-12 text-center">
              <Loader2 className="w-12 h-12 text-primary-500 animate-spin mx-auto mb-4" />
              <h3 className="text-base font-semibold text-slate-700 dark:text-slate-200">
                AI 正在分析中...
              </h3>
              <p className="text-sm text-slate-400 dark:text-slate-500 mt-2">
                正在解析 JD 要求并逐项比对简历内容，通常需要 10-30 秒
              </p>
            </div>
          ) : (
            <>
              {/* 总分 + 维度 */}
              <motion.div
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl p-6"
              >
                <div className="flex items-center justify-between mb-4">
                  <div className="flex items-center gap-2">
                    <span className="w-7 h-7 rounded-lg bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400 flex items-center justify-center">
                      <Target className="w-4 h-4" />
                    </span>
                    <h2 className="text-base font-semibold text-slate-800 dark:text-white">
                      {detail.jdTitle || '岗位匹配'} · {detail.resumeFilename}
                    </h2>
                  </div>
                  {level && <span className={`text-sm font-semibold ${level.color}`}>{level.label}</span>}
                </div>

                <div className="flex flex-col sm:flex-row items-center gap-6">
                  <ScoreRing score={detail.overallScore ?? 0} />
                  <div className="flex-1 w-full space-y-4">
                    {detail.scoreDetail && DIMENSIONS.map(dim => (
                      <DimensionBar
                        key={dim.key}
                        label={dim.label}
                        desc={dim.desc}
                        score={detail.scoreDetail![dim.key] ?? 0}
                        max={dim.max}
                      />
                    ))}
                  </div>
                </div>

                {detail.summary && (
                  <p className="mt-4 text-sm text-slate-600 dark:text-slate-300 bg-slate-50 dark:bg-slate-800 border border-slate-100 dark:border-slate-700 rounded-lg p-3">
                    {detail.summary}
                  </p>
                )}
              </motion.div>

              {/* JD 核心要求 */}
              <SectionCard icon={<ClipboardList className="w-4 h-4" />} title="JD 核心要求覆盖">
                {detail.jdRequirements.length === 0 ? (
                  <p className="text-sm text-slate-400">暂无要求清单</p>
                ) : (
                  <div className="space-y-2">
                    {detail.jdRequirements.map((req, idx) => (
                      <div
                        key={idx}
                        className="flex items-start gap-3 p-3 rounded-lg border border-slate-200 dark:border-slate-700"
                      >
                        <div className="flex flex-col gap-1 mt-0.5 shrink-0">
                          <span className={`text-xs px-2 py-0.5 rounded-full text-center ${
                            req.priority === 'CORE'
                              ? 'bg-red-50 dark:bg-red-900/30 text-red-600 dark:text-red-400'
                              : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400'
                          }`}>
                            {req.priority === 'CORE' ? '核心' : '加分'}
                          </span>
                          <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-slate-50 dark:bg-slate-800 text-slate-400 text-center">
                            {req.category}
                          </span>
                        </div>
                        <div className="flex-1 min-w-0">
                          <p className="text-sm text-slate-700 dark:text-slate-200">{req.requirement}</p>
                          {req.matched && req.evidence && (
                            <p className="text-xs text-emerald-600 dark:text-emerald-400 mt-1">
                              简历体现：{req.evidence}
                            </p>
                          )}
                        </div>
                        {req.matched ? (
                          <CheckCircle2 className="w-5 h-5 text-emerald-500 shrink-0 mt-0.5" />
                        ) : (
                          <XCircle className="w-5 h-5 text-red-400 shrink-0 mt-0.5" />
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </SectionCard>

              {/* 优势 + 差距 */}
              <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                <SectionCard icon={<CheckCircle2 className="w-4 h-4" />} title="匹配优势">
                  {detail.strengths.length === 0 ? (
                    <p className="text-sm text-slate-400">暂无突出优势</p>
                  ) : (
                    <ul className="space-y-2">
                      {detail.strengths.map((s, idx) => (
                        <li key={idx} className="flex items-start gap-2 text-sm text-slate-600 dark:text-slate-300">
                          <CheckCircle2 className="w-4 h-4 text-emerald-500 shrink-0 mt-0.5" />
                          <span>{s}</span>
                        </li>
                      ))}
                    </ul>
                  )}
                </SectionCard>

                <SectionCard icon={<AlertCircle className="w-4 h-4" />} title="差距与风险">
                  {detail.gaps.length === 0 ? (
                    <p className="text-sm text-slate-400">无明显差距</p>
                  ) : (
                    <div className="space-y-3">
                      {detail.gaps.map((g, idx) => (
                        <div key={idx} className="p-3 rounded-lg bg-red-50 dark:bg-red-900/10 border border-red-100 dark:border-red-900/30">
                          <div className="flex items-start justify-between gap-2">
                            <p className="text-sm font-medium text-slate-700 dark:text-slate-200">{g.gap}</p>
                            <span className={`text-xs px-1.5 py-0.5 rounded-full shrink-0 ${
                              g.impact === '高'
                                ? 'bg-red-100 dark:bg-red-900/40 text-red-600 dark:text-red-400'
                                : g.impact === '中'
                                  ? 'bg-amber-100 dark:bg-amber-900/40 text-amber-600 dark:text-amber-400'
                                  : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400'
                            }`}>
                              影响{g.impact}
                            </span>
                          </div>
                          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1.5">{g.suggestion}</p>
                        </div>
                      ))}
                    </div>
                  )}
                </SectionCard>
              </div>

              {/* 优化建议 */}
              <SectionCard icon={<Sparkles className="w-4 h-4" />} title="简历优化建议">
                {detail.suggestions.length === 0 ? (
                  <p className="text-sm text-slate-400">暂无优化建议</p>
                ) : (
                  <div className="space-y-3">
                    {detail.suggestions.map((s, idx) => (
                      <div key={idx} className="p-4 rounded-lg border border-slate-200 dark:border-slate-700">
                        <div className="flex items-center gap-2 mb-2">
                          <span className="text-xs px-2 py-0.5 rounded-full bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400">
                            {s.category}
                          </span>
                          <span className={`text-xs px-2 py-0.5 rounded-full ${
                            s.priority === '高'
                              ? 'bg-red-50 dark:bg-red-900/30 text-red-600 dark:text-red-400'
                              : s.priority === '中'
                                ? 'bg-amber-50 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400'
                                : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400'
                          }`}>
                            {s.priority}优先级
                          </span>
                        </div>
                        <p className="text-sm font-medium text-slate-700 dark:text-slate-200">{s.issue}</p>
                        <p className="text-sm text-slate-500 dark:text-slate-400 mt-1.5">{s.recommendation}</p>
                      </div>
                    ))}
                  </div>
                )}
              </SectionCard>

              {/* 面试问题 */}
              {detail.interviewQuestions.length > 0 && (
                <SectionCard icon={<FileText className="w-4 h-4" />} title="可能被追问的面试问题">
                  <ul className="space-y-2">
                    {detail.interviewQuestions.map((q, idx) => (
                      <li key={idx} className="flex items-start gap-2 text-sm text-slate-600 dark:text-slate-300">
                        <ArrowRight className="w-4 h-4 text-primary-400 shrink-0 mt-0.5" />
                        <span>{q}</span>
                      </li>
                    ))}
                  </ul>
                </SectionCard>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
