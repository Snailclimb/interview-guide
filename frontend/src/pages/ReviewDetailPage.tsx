import { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  ArrowLeft,
  Check,
  ChevronDown,
  Edit3,
  Loader2,
  PanelLeftClose,
  PanelLeftOpen,
  Sparkles,
  X,
} from 'lucide-react';
import { reviewApi } from '../api/review';
import CodeBlock from '../components/CodeBlock';
import { formatDateOnly } from '../utils/date';
import {
  ARTIFACT_TYPE_LABELS,
  type ArtifactDTO,
  type ArtifactType,
  type AsyncTaskStatus,
  type ReviewDetailDTO,
} from '../types/review';

const ARTIFACT_TYPES: ArtifactType[] = [
  'QUESTION_ANALYSIS',
  'PROJECT_ANALYSIS',
  'QUESTION_RECORD',
];

function isArtifactAnalyzing(status: AsyncTaskStatus | null | undefined): boolean {
  return status === 'PENDING' || status === 'PROCESSING';
}

function isArtifactEditable(status: AsyncTaskStatus | null | undefined): boolean {
  return status === 'COMPLETED' || status === 'FAILED';
}

export default function ReviewDetailPage() {
  const { reviewId } = useParams<{ reviewId: string }>();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<ReviewDetailDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [transcriptOpen, setTranscriptOpen] = useState(true);
  const [analyzingTypes, setAnalyzingTypes] = useState<Set<ArtifactType>>(new Set());
  const [editingArtifactId, setEditingArtifactId] = useState<number | null>(null);
  const [editContent, setEditContent] = useState('');
  const [savingEdit, setSavingEdit] = useState(false);
  const [expandedTypes, setExpandedTypes] = useState<Set<ArtifactType>>(new Set(ARTIFACT_TYPES));

  const markdownComponents = useMemo(() => ({
    h1: ({ children }: any) => (
      <h1 className="mt-10 mb-6 flex items-center gap-3 border-b-2 border-primary-500/20 pb-3 text-3xl font-extrabold text-slate-900 dark:text-white">
        <span className="h-8 w-1.5 shrink-0 rounded-full bg-primary-500" />
        {children}
      </h1>
    ),
    h2: ({ children }: any) => (
      <h2 className="mt-8 mb-4 flex items-center gap-2 text-2xl font-bold text-slate-800 dark:text-slate-100">
        <span className="h-6 w-1 shrink-0 rounded-full bg-primary-400/50" />
        {children}
      </h2>
    ),
    h3: ({ children }: any) => (
      <h3 className="mt-6 mb-3 border-l-4 border-slate-200 pl-3 text-xl font-semibold text-slate-800 dark:border-slate-700 dark:text-slate-200">
        {children}
      </h3>
    ),
    p: ({ children }: any) => (
      <p className="my-4 text-[1.05rem] leading-relaxed text-slate-700 dark:text-slate-300">
        {children}
      </p>
    ),
    ul: ({ children }: any) => (
      <ul className="my-4 ml-6 list-outside list-disc space-y-2 text-slate-700 dark:text-slate-300">
        {children}
      </ul>
    ),
    ol: ({ children }: any) => (
      <ol className="my-4 ml-6 list-outside list-decimal space-y-2 text-slate-700 dark:text-slate-300">
        {children}
      </ol>
    ),
    li: ({ children }: any) => <li className="pl-1 leading-relaxed">{children}</li>,
    blockquote: ({ children }: any) => (
      <blockquote className="my-6 rounded-r-xl border-l-4 border-primary-500 bg-primary-50/30 px-5 py-3 italic text-slate-600 shadow-sm dark:bg-primary-900/10 dark:text-slate-400">
        {children}
      </blockquote>
    ),
    code: ({ className, children, ...props }: any) => {
      const match = /language-(\w+)/.exec(className || '');
      if (!match) {
        return (
          <code
            className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[0.9em] font-medium text-pink-600 dark:bg-slate-700/50 dark:text-pink-400"
            {...props}
          >
            {children}
          </code>
        );
      }

      return (
        <div className="my-8 overflow-hidden rounded-xl border border-slate-200 shadow-md ring-4 ring-slate-50 dark:border-slate-700 dark:ring-slate-900/50">
          <CodeBlock language={match[1]}>{String(children).replace(/\n$/, '')}</CodeBlock>
        </div>
      );
    },
    pre: ({ children }: any) => <>{children}</>,
    a: ({ href, children }: any) => (
      <a
        href={href}
        className="font-bold text-primary-600 decoration-2 underline-offset-4 hover:underline dark:text-primary-400"
        target="_blank"
        rel="noopener noreferrer"
      >
        {children}
      </a>
    ),
    table: ({ children }: any) => (
      <div className="my-6 w-full overflow-x-auto rounded-xl border border-slate-200 shadow-sm dark:border-slate-700">
        <table className="min-w-full w-max table-auto border-collapse text-left text-sm">
          {children}
        </table>
      </div>
    ),
    thead: ({ children }: any) => (
      <thead className="border-b border-slate-200 bg-slate-50 text-slate-900 dark:border-slate-700 dark:bg-slate-800/50 dark:text-slate-100">
        {children}
      </thead>
    ),
    tbody: ({ children }: any) => (
      <tbody className="divide-y divide-slate-100 bg-white dark:divide-slate-700/50 dark:bg-slate-900/20">
        {children}
      </tbody>
    ),
    tr: ({ children }: any) => (
      <tr className="transition-colors hover:bg-slate-50/50 dark:hover:bg-slate-800/30">
        {children}
      </tr>
    ),
    th: ({ children }: any) => (
      <th className="border-x border-transparent px-4 py-3 font-semibold">
        <div className="min-w-[100px] whitespace-nowrap">{children}</div>
      </th>
    ),
    td: ({ children }: any) => (
      <td className="align-top border-x border-transparent px-4 py-3">
        <div className="min-w-[150px] max-w-[400px] break-words whitespace-pre-wrap">{children}</div>
      </td>
    ),
    hr: () => <hr className="my-8 border-t border-slate-200 dark:border-slate-700" />,
  }), []);

  const loadDetail = useCallback(async () => {
    if (!reviewId) {
      return;
    }
    setLoading(true);
    try {
      const data = await reviewApi.getDetail(Number(reviewId));
      setDetail(data);
      setError('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [reviewId]);

  useEffect(() => {
    loadDetail();
  }, [loadDetail]);

  const getArtifact = useCallback((type: ArtifactType): ArtifactDTO | undefined => {
    return detail?.artifacts.find((artifact) => artifact.type === type);
  }, [detail]);

  const handleAnalyze = useCallback(async (type: ArtifactType) => {
    if (!reviewId || analyzingTypes.has(type)) {
      return;
    }
    setAnalyzingTypes((prev) => new Set(prev).add(type));
    try {
      const artifact = await reviewApi.analyze(Number(reviewId), { type });
      setDetail((prev) => {
        if (!prev) {
          return prev;
        }
        const existingIndex = prev.artifacts.findIndex((item) => item.type === type);
        const artifacts = [...prev.artifacts];
        if (existingIndex >= 0) {
          artifacts[existingIndex] = artifact;
        } else {
          artifacts.push(artifact);
        }
        return { ...prev, artifacts };
      });
    } catch (err) {
      alert(err instanceof Error ? err.message : '分析失败');
    } finally {
      setAnalyzingTypes((prev) => {
        const next = new Set(prev);
        next.delete(type);
        return next;
      });
    }
  }, [reviewId, analyzingTypes]);

  useEffect(() => {
    if (!reviewId) {
      return;
    }
    const hasActive = detail?.artifacts.some((artifact) => isArtifactAnalyzing(artifact.status));
    if (!hasActive) {
      return;
    }

    const timer = setInterval(async () => {
      try {
        const statuses = await reviewApi.getArtifactStatuses(Number(reviewId));
        const stillActive = statuses.some((artifact) => isArtifactAnalyzing(artifact.status));
        setDetail((prev) => {
          if (!prev) {
            return prev;
          }
          return {
            ...prev,
            artifacts: prev.artifacts.map((existing) => {
              const updated = statuses.find((item) => item.type === existing.type);
              return updated
                ? {
                    ...existing,
                    status: updated.status,
                    error: updated.error,
                    version: updated.version,
                    content: updated.content || existing.content,
                  }
                : existing;
            }),
          };
        });

        if (!stillActive) {
          clearInterval(timer);
          loadDetail();
        }
      } catch {
        // Ignore polling errors.
      }
    }, 3000);

    return () => clearInterval(timer);
  }, [reviewId, detail?.artifacts, loadDetail]);

  const handleStartEdit = (artifact: ArtifactDTO) => {
    setEditingArtifactId(artifact.id);
    setEditContent(artifact.content);
  };

  const handleSaveEdit = async () => {
    if (!reviewId || editingArtifactId == null) {
      return;
    }
    setSavingEdit(true);
    try {
      const updated = await reviewApi.updateArtifact(Number(reviewId), editingArtifactId, editContent);
      setDetail((prev) => {
        if (!prev) {
          return prev;
        }
        return {
          ...prev,
          artifacts: prev.artifacts.map((artifact) =>
            artifact.id === editingArtifactId ? updated : artifact
          ),
        };
      });
      setEditingArtifactId(null);
    } catch (err) {
      alert(err instanceof Error ? err.message : '保存失败');
    } finally {
      setSavingEdit(false);
    }
  };

  const preprocessMarkdown = (content: string) => {
    if (!content) {
      return '';
    }
    const trimmed = content.trim();
    const match = trimmed.match(/^```(?:markdown|text|)?\n?([\s\S]*?)\n?```$/i);
    return match ? match[1].trim() : trimmed;
  };

  if (loading) {
    return (
      <div className="flex min-h-[50vh] items-center justify-center">
        <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
      </div>
    );
  }

  if (error || !detail) {
    return (
      <div className="flex min-h-[50vh] items-center justify-center">
        <div className="text-center">
          <p className="mb-4 text-red-500">{error || '复盘记录不存在'}</p>
          <button
            onClick={() => navigate('/reviews')}
            className="rounded-lg bg-primary-500 px-5 py-2 text-white hover:bg-primary-600"
          >
            返回列表
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-[calc(100vh-5rem)] flex-col">
      <div className="mb-4 flex items-center gap-3 shrink-0">
        <button
          onClick={() => navigate('/reviews')}
          className="rounded-lg p-2 text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-slate-700 dark:hover:text-slate-300"
        >
          <ArrowLeft className="h-5 w-5" />
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-xl font-bold text-slate-900 dark:text-white">
            {detail.companyName}
          </h1>
          <p className="text-sm text-slate-500 dark:text-slate-400">
            {detail.position} · 第{detail.roundNumber}轮 · {formatDateOnly(detail.interviewDate)}
          </p>
        </div>
        {detail.artifacts.some((artifact) => isArtifactAnalyzing(artifact.status)) && (
          <button
            onClick={async () => {
              try {
                await reviewApi.cancelAnalysis(Number(reviewId));
                await loadDetail();
              } catch (err) {
                alert(err instanceof Error ? err.message : '取消失败');
              }
            }}
            className="flex items-center gap-1.5 rounded-lg bg-red-50 px-3 py-1.5 text-sm font-medium text-red-600 transition-colors hover:bg-red-100 dark:bg-red-900/30 dark:text-red-400 dark:hover:bg-red-900/50"
          >
            <X className="h-4 w-4" />
            终止分析
          </button>
        )}
      </div>

      <div className="flex min-h-0 flex-1 gap-4">
        <AnimatePresence mode="wait">
          {transcriptOpen && (
            <motion.div
              initial={{ width: 0, opacity: 0 }}
              animate={{ width: '40%', opacity: 1 }}
              exit={{ width: 0, opacity: 0 }}
              transition={{ duration: 0.2 }}
              className="min-w-0 overflow-hidden rounded-2xl border border-slate-100 bg-white dark:border-slate-700 dark:bg-slate-800"
            >
              <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3 dark:border-slate-700">
                <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">面试记录</h2>
                <button
                  onClick={() => setTranscriptOpen(false)}
                  className="rounded p-1 text-slate-400 transition-colors hover:text-slate-600 dark:hover:text-slate-300"
                >
                  <PanelLeftClose className="h-4 w-4" />
                </button>
              </div>
              <div className="h-full overflow-y-auto p-4">
                <div className="notion-render-container prose prose-slate max-w-none dark:prose-invert">
                  <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
                    {preprocessMarkdown(detail.transcriptText || '暂无面试记录文本')}
                  </ReactMarkdown>
                </div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {!transcriptOpen && (
          <button
            onClick={() => setTranscriptOpen(true)}
            className="mt-3 self-start rounded-lg p-1.5 text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-slate-700 dark:hover:text-slate-300"
            title="显示面试记录"
          >
            <PanelLeftOpen className="h-5 w-5" />
          </button>
        )}

        <div className="min-w-0 flex-1 overflow-y-auto">
          <div className="space-y-3">
            {ARTIFACT_TYPES.map((type) => {
              const artifact = getArtifact(type);
              const isAnalyzing = analyzingTypes.has(type) || isArtifactAnalyzing(artifact?.status);
              const isFailed = artifact?.status === 'FAILED';
              const isEditing = editingArtifactId === artifact?.id;
              const canEdit = artifact ? isArtifactEditable(artifact.status) : false;

              return (
                <div
                  key={type}
                  className="overflow-hidden rounded-2xl border border-slate-100 bg-white dark:border-slate-700 dark:bg-slate-800"
                >
                  <div
                    role="button"
                    aria-expanded={expandedTypes.has(type)}
                    onClick={() => {
                      setExpandedTypes((prev) => {
                        const next = new Set(prev);
                        if (next.has(type)) {
                          next.delete(type);
                        } else {
                          next.add(type);
                        }
                        return next;
                      });
                    }}
                    className="flex cursor-pointer items-center justify-between border-b border-slate-100 px-4 py-3 transition-colors hover:bg-slate-50 dark:border-slate-700 dark:hover:bg-slate-700/30"
                  >
                    <div className="flex items-center gap-2">
                      <ChevronDown
                        className={`h-4 w-4 text-slate-400 transition-transform ${
                          expandedTypes.has(type) ? 'rotate-0' : '-rotate-90'
                        }`}
                      />
                      <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300">
                        {ARTIFACT_TYPE_LABELS[type]}
                      </h3>
                      {artifact?.version ? (
                        <span className="rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-400 dark:bg-slate-700 dark:text-slate-500">
                          v{artifact.version}
                        </span>
                      ) : null}
                      {isAnalyzing && (
                        <span className="animate-pulse rounded bg-blue-50 px-1.5 py-0.5 text-xs text-blue-500 dark:bg-blue-900/30">
                          分析中
                        </span>
                      )}
                      {isFailed && (
                        <span className="rounded bg-red-50 px-1.5 py-0.5 text-xs text-red-500 dark:bg-red-900/30">
                          失败
                        </span>
                      )}
                    </div>
                    <div className="flex items-center gap-1.5" onClick={(event) => event.stopPropagation()}>
                      {!artifact && !isAnalyzing && (
                        <button
                          onClick={() => handleAnalyze(type)}
                          className="flex items-center gap-1 rounded-lg bg-primary-50 px-3 py-1.5 text-xs font-medium text-primary-600 transition-colors hover:bg-primary-100 dark:bg-primary-900/30 dark:text-primary-400 dark:hover:bg-primary-900/50"
                        >
                          <Sparkles className="h-3 w-3" />
                          开始分析
                        </button>
                      )}
                      {isFailed && (
                        <button
                          onClick={() => handleAnalyze(type)}
                          disabled={isAnalyzing}
                          className="flex items-center gap-1 rounded-lg bg-primary-50 px-3 py-1.5 text-xs font-medium text-primary-600 transition-colors hover:bg-primary-100 disabled:opacity-50 dark:bg-primary-900/30 dark:text-primary-400 dark:hover:bg-primary-900/50"
                        >
                          {isAnalyzing ? (
                            <Loader2 className="h-3 w-3 animate-spin" />
                          ) : (
                            <Sparkles className="h-3 w-3" />
                          )}
                          {isAnalyzing ? '分析中...' : '重新分析'}
                        </button>
                      )}
                      {artifact && canEdit && !isEditing && (
                        <button
                          onClick={() => handleStartEdit(artifact)}
                          className="flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs font-medium text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-700 dark:text-slate-400 dark:hover:bg-slate-700 dark:hover:text-slate-300"
                        >
                          <Edit3 className="h-3 w-3" />
                          编辑
                        </button>
                      )}
                      {artifact && isEditing && (
                        <>
                          <button
                            onClick={handleSaveEdit}
                            disabled={savingEdit}
                            className="flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs font-medium text-emerald-600 transition-colors hover:bg-emerald-50 disabled:opacity-50 dark:text-emerald-400 dark:hover:bg-emerald-900/30"
                          >
                            {savingEdit ? (
                              <Loader2 className="h-3 w-3 animate-spin" />
                            ) : (
                              <Check className="h-3 w-3" />
                            )}
                            保存
                          </button>
                          <button
                            onClick={() => {
                              setEditingArtifactId(null);
                              setEditContent('');
                            }}
                            className="flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs font-medium text-slate-400 transition-colors hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-slate-700 dark:hover:text-slate-300"
                          >
                            <X className="h-3 w-3" />
                            取消
                          </button>
                        </>
                      )}
                    </div>
                  </div>

                  <AnimatePresence initial={false}>
                    {expandedTypes.has(type) && (
                      <motion.div
                        initial={{ height: 0, opacity: 0 }}
                        animate={{ height: 'auto', opacity: 1 }}
                        exit={{ height: 0, opacity: 0 }}
                        transition={{ duration: 0.2 }}
                        className="overflow-hidden"
                      >
                        <div className="border-t border-slate-100 p-4 dark:border-slate-700">
                          {isAnalyzing && !artifact?.content && (
                            <div className="flex items-center justify-center py-8 text-slate-400">
                              <Loader2 className="mr-2 h-5 w-5 animate-spin" />
                              AI 正在分析...
                            </div>
                          )}
                          {isFailed && artifact?.error && (
                            <div className="mb-3 rounded-lg bg-red-50 p-3 text-sm text-red-500 dark:bg-red-900/20">
                              {artifact.error}
                            </div>
                          )}
                          {isEditing && artifact ? (
                            <textarea
                              value={editContent}
                              onChange={(event) => setEditContent(event.target.value)}
                              className="h-48 w-full resize-y rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-slate-600 dark:bg-slate-700 dark:text-white"
                              disabled={savingEdit}
                            />
                          ) : artifact ? (
                            <div className="notion-render-container prose prose-slate max-w-none dark:prose-invert">
                              <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
                                {preprocessMarkdown(artifact.content)}
                              </ReactMarkdown>
                            </div>
                          ) : (
                            <div className="py-8 text-center text-sm text-slate-400">
                              暂无分析内容
                            </div>
                          )}
                        </div>
                      </motion.div>
                    )}
                  </AnimatePresence>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
