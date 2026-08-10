import {useCallback, useEffect, useState} from 'react';
import {AnimatePresence, motion} from 'framer-motion';
import {AlertCircle, GitCompareArrows, Loader2, TrendingDown, TrendingUp, X} from 'lucide-react';
import {historyApi, ResumeVersion, type AnalysisItem} from '../api/history';
import {formatDateOnly} from '../utils/date';

export interface VersionCompareModalProps {
  open: boolean;
  /** 版本链 */
  versions: ResumeVersion[];
  /** 当前版本 id（默认对比基准 A） */
  currentId: number;
  onClose: () => void;
}

interface AnalysisBundle {
  version: ResumeVersion;
  analysis: AnalysisItem | null;
}

// 五个评分维度（与 AnalysisPanel 保持一致）：满分分别为 40/20/15/15/10
const DIMENSIONS: { key: keyof AnalysisItem; label: string; fullMark: number; color: string }[] = [
  {key: 'projectScore', label: '项目经验', fullMark: 40, color: 'bg-purple-500'},
  {key: 'skillMatchScore', label: '技能匹配', fullMark: 20, color: 'bg-blue-500'},
  {key: 'contentScore', label: '内容完整性', fullMark: 15, color: 'bg-emerald-500'},
  {key: 'structureScore', label: '结构清晰度', fullMark: 15, color: 'bg-cyan-500'},
  {key: 'expressionScore', label: '表达专业性', fullMark: 10, color: 'bg-orange-500'},
];

function DiffBadge({diff}: { diff: number | null }) {
  if (diff === null || diff === 0) return null;
  const up = diff > 0;
  return (
    <span
      className={`inline-flex items-center gap-0.5 px-2 py-0.5 rounded-full text-xs font-bold ${
        up
          ? 'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-600 dark:text-emerald-400'
          : 'bg-red-100 dark:bg-red-900/40 text-red-600 dark:text-red-400'
      }`}
    >
      {up ? <TrendingUp className="w-3 h-3"/> : <TrendingDown className="w-3 h-3"/>}
      {up ? '+' : ''}{diff}
    </span>
  );
}

function ScoreBar({label, score, fullMark, color}: { label: string; score: number; fullMark: number; color: string }) {
  const pct = Math.min(100, (score / fullMark) * 100);
  return (
    <div>
      <div className="flex items-center justify-between mb-1">
        <span className="text-xs text-slate-500 dark:text-slate-400">{label}</span>
        <span className="text-xs font-semibold text-slate-700 dark:text-slate-300">{score}/{fullMark}</span>
      </div>
      <div className="w-full h-2 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
        <div className={`h-full ${color} rounded-full transition-all duration-700`} style={{width: `${pct}%`}}/>
      </div>
    </div>
  );
}

/**
 * 简历版本对比弹窗：并排展示两个版本的评分（总分 + 五维）并标注差值
 */
export default function VersionCompareModal({open, versions, currentId, onClose}: VersionCompareModalProps) {
  const [versionAId, setVersionAId] = useState<number>(currentId);
  const [versionBId, setVersionBId] = useState<number | null>(null);
  const [bundleA, setBundleA] = useState<AnalysisBundle | null>(null);
  const [bundleB, setBundleB] = useState<AnalysisBundle | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  // 打开时重置选择：A = 当前版本，B = 上一个版本（若无则选第一个其他版本）
  useEffect(() => {
    if (!open || versions.length === 0) return;
    setVersionAId(currentId);
    const other = versions.find(v => v.id !== currentId);
    setVersionBId(other ? other.id : null);
  }, [open, versions, currentId]);

  const loadBundle = useCallback(async (id: number): Promise<AnalysisBundle> => {
    const version = versions.find(v => v.id === id);
    const detail = await historyApi.getResumeDetail(id);
    return {version: version!, analysis: detail.analyses?.[0] ?? null};
  }, [versions]);

  useEffect(() => {
    if (!open || versionAId === null || versionBId === null) return;
    let cancelled = false;
    setLoading(true);
    setError('');
    Promise.all([loadBundle(versionAId), loadBundle(versionBId)])
      .then(([a, b]) => {
        if (cancelled) return;
        setBundleA(a);
        setBundleB(b);
      })
      .catch(() => {
        if (!cancelled) setError('加载版本数据失败，请重试');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, versionAId, versionBId, loadBundle]);

  const selectableA = versions.filter(v => v.id !== versionBId);
  const selectableB = versions.filter(v => v.id !== versionAId);

  const diff = (field: keyof AnalysisItem): number | null => {
    if (!bundleA?.analysis || !bundleB?.analysis) return null;
    const a = Number(bundleA.analysis[field]) || 0;
    const b = Number(bundleB.analysis[field]) || 0;
    return a - b;
  };

  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            initial={{opacity: 0}}
            animate={{opacity: 1}}
            exit={{opacity: 0}}
            onClick={onClose}
            className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
          />
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <motion.div
              initial={{opacity: 0, scale: 0.95, y: 20}}
              animate={{opacity: 1, scale: 1, y: 0}}
              exit={{opacity: 0, scale: 0.95, y: 20}}
              onClick={(e) => e.stopPropagation()}
              className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-4xl w-full p-6 max-h-[90vh] overflow-y-auto"
            >
              {/* 标题 */}
              <div className="flex items-center justify-between mb-5">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 bg-indigo-100 dark:bg-indigo-900/50 rounded-xl flex items-center justify-center">
                    <GitCompareArrows className="w-5 h-5 text-indigo-600 dark:text-indigo-400"/>
                  </div>
                  <div>
                    <h3 className="text-xl font-bold text-slate-900 dark:text-white">版本评分对比</h3>
                    <p className="text-sm text-slate-500 dark:text-slate-400">对比两个版本的 AI 分析评分，验证优化效果</p>
                  </div>
                </div>
                <button
                  onClick={onClose}
                  className="w-9 h-9 bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-300 rounded-xl hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors flex items-center justify-center"
                >
                  <X className="w-5 h-5"/>
                </button>
              </div>

              {/* 版本选择 */}
              <div className="grid grid-cols-2 gap-4 mb-6">
                <div>
                  <label className="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1.5">版本 A</label>
                  <select
                    value={versionAId ?? ''}
                    onChange={(e) => setVersionAId(Number(e.target.value))}
                    className="w-full px-3 py-2.5 border border-slate-200 dark:border-slate-600 rounded-xl bg-white dark:bg-slate-700 text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500"
                  >
                    {selectableA.map(v => (
                      <option key={v.id} value={v.id}>
                        v{v.versionNo} · {v.latestScore ?? '--'} 分
                        {v.current ? '（当前）' : ''}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1.5">版本 B</label>
                  <select
                    value={versionBId ?? ''}
                    onChange={(e) => setVersionBId(Number(e.target.value))}
                    className="w-full px-3 py-2.5 border border-slate-200 dark:border-slate-600 rounded-xl bg-white dark:bg-slate-700 text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500"
                  >
                    {selectableB.map(v => (
                      <option key={v.id} value={v.id}>
                        v{v.versionNo} · {v.latestScore ?? '--'} 分
                        {v.current ? '（当前）' : ''}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              {versions.length < 2 && !loading && (
                <div className="py-12 text-center text-slate-500 dark:text-slate-400">
                  该简历目前只有一个版本，上传优化后的新版本后可在此对比
                </div>
              )}

              {loading && (
                <div className="py-12 flex flex-col items-center gap-3 text-slate-500 dark:text-slate-400">
                  <Loader2 className="w-8 h-8 text-primary-500 animate-spin"/>
                  <span>加载版本评分中...</span>
                </div>
              )}

              {error && !loading && (
                <div className="py-8 flex items-center justify-center gap-2 text-red-500 dark:text-red-400">
                  <AlertCircle className="w-5 h-5"/>
                  {error}
                </div>
              )}

              {!loading && !error && bundleA && bundleB && versions.length >= 2 && (
                <div>
                  {/* 总分对比 */}
                  <div className="grid grid-cols-[1fr_auto_1fr] items-stretch gap-4 mb-6">
                    <div className="bg-slate-50 dark:bg-slate-700/50 rounded-xl p-5 text-center">
                      <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">
                        v{bundleA.version.versionNo} 总分
                      </p>
                      <p className="text-4xl font-bold text-slate-900 dark:text-white">
                        {bundleA.analysis?.overallScore ?? '--'}
                      </p>
                      <p className="text-xs text-slate-400 mt-1">{formatDateOnly(bundleA.version.uploadedAt)}</p>
                    </div>
                    <div className="flex flex-col items-center justify-center gap-1 px-2">
                      <span className="text-lg font-bold text-slate-300 dark:text-slate-500">→</span>
                      <DiffBadge diff={diff('overallScore')}/>
                    </div>
                    <div className="bg-slate-50 dark:bg-slate-700/50 rounded-xl p-5 text-center">
                      <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">
                        v{bundleB.version.versionNo} 总分
                      </p>
                      <p className="text-4xl font-bold text-slate-900 dark:text-white">
                        {bundleB.analysis?.overallScore ?? '--'}
                      </p>
                      <p className="text-xs text-slate-400 mt-1">{formatDateOnly(bundleB.version.uploadedAt)}</p>
                    </div>
                  </div>

                  {/* 五维对比 */}
                  <div className="space-y-4">
                    {DIMENSIONS.map(dim => (
                      <div key={dim.key} className="grid grid-cols-[1fr_auto_1fr] items-center gap-4">
                        <ScoreBar
                          label={dim.label}
                          score={Number(bundleA.analysis?.[dim.key]) || 0}
                          fullMark={dim.fullMark}
                          color={dim.color}
                        />
                        <div className="w-14 text-center">
                          <DiffBadge diff={diff(dim.key)}/>
                        </div>
                        <ScoreBar
                          label={dim.label}
                          score={Number(bundleB.analysis?.[dim.key]) || 0}
                          fullMark={dim.fullMark}
                          color={dim.color}
                        />
                      </div>
                    ))}
                  </div>

                  {/* 摘要对比 */}
                  <div className="grid grid-cols-2 gap-4 mt-6">
                    {[bundleA, bundleB].map((b, idx) => (
                      <div key={idx} className="bg-slate-50 dark:bg-slate-700/50 rounded-xl p-4">
                        <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2">
                          v{b.version.versionNo} 核心评价
                          {b.version.versionNote && (
                            <span className="block text-slate-400 font-normal mt-0.5">「{b.version.versionNote}」</span>
                          )}
                        </p>
                        <p className="text-sm text-slate-700 dark:text-slate-300 leading-relaxed">
                          {b.analysis?.summary || '暂无评价'}
                        </p>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </motion.div>
          </div>
        </>
      )}
    </AnimatePresence>
  );
}
