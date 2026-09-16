import {
  AlertCircle,
  CheckCircle2,
  Clock3,
  FileText,
  Loader2,
  RefreshCw,
  X,
} from 'lucide-react';

import {
  MAX_BATCH_FILES,
  type BatchUploadItem,
  type BatchUploadStatus,
} from '../../pages/knowledgeBaseBatchUpload';

interface KnowledgeBaseUploadListProps {
  items: BatchUploadItem[];
  completedCount: number;
  failedCount: number;
  hasUploadActivity: boolean;
  revectorizingId: number | null;
  onClear: () => void;
  onNameChange: (clientId: string, customName: string) => void;
  onRemove: (clientId: string) => void;
  onRetryUpload: (clientId: string) => void;
  onRevectorize: (clientId: string) => void;
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function StatusBadge({ status }: { status: BatchUploadStatus }) {
  const styles = 'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium';

  switch (status) {
    case 'READY':
      return (
        <span className={`${styles} bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300`}>
          <Clock3 className="h-3.5 w-3.5" />待提交
        </span>
      );
    case 'UPLOADING':
      return (
        <span className={`${styles} bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-300`}>
          <Loader2 className="h-3.5 w-3.5 animate-spin" />上传中
        </span>
      );
    case 'PENDING':
      return (
        <span className={`${styles} bg-amber-50 text-amber-600 dark:bg-amber-900/30 dark:text-amber-300`}>
          <Clock3 className="h-3.5 w-3.5" />等待向量化
        </span>
      );
    case 'PROCESSING':
      return (
        <span className={`${styles} bg-violet-50 text-violet-600 dark:bg-violet-900/30 dark:text-violet-300`}>
          <Loader2 className="h-3.5 w-3.5 animate-spin" />向量化中
        </span>
      );
    case 'COMPLETED':
      return (
        <span className={`${styles} bg-emerald-50 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300`}>
          <CheckCircle2 className="h-3.5 w-3.5" />已完成
        </span>
      );
    case 'UPLOAD_FAILED':
      return (
        <span className={`${styles} bg-red-50 text-red-600 dark:bg-red-900/30 dark:text-red-300`}>
          <AlertCircle className="h-3.5 w-3.5" />上传失败
        </span>
      );
    case 'VECTOR_FAILED':
      return (
        <span className={`${styles} bg-red-50 text-red-600 dark:bg-red-900/30 dark:text-red-300`}>
          <AlertCircle className="h-3.5 w-3.5" />向量化失败
        </span>
      );
    case 'QUEUED':
      return (
        <span className={`${styles} bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300`}>
          <Clock3 className="h-3.5 w-3.5" />等待上传
        </span>
      );
  }
}

export default function KnowledgeBaseUploadList({
  items,
  completedCount,
  failedCount,
  hasUploadActivity,
  revectorizingId,
  onClear,
  onNameChange,
  onRemove,
  onRetryUpload,
  onRevectorize,
}: KnowledgeBaseUploadListProps) {
  if (items.length === 0) return null;

  return (
    <section className="mt-6 overflow-hidden rounded-2xl bg-white shadow-lg dark:bg-slate-800 dark:shadow-slate-900/50">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-6 py-4 dark:border-slate-700">
        <div>
          <h2 className="font-semibold text-slate-900 dark:text-white">
            文件列表（{items.length}/{MAX_BATCH_FILES}）
          </h2>
          <p className="mt-1 text-xs text-slate-500 dark:text-slate-400" aria-live="polite">
            已完成 {completedCount} 个{failedCount > 0 ? `，失败 ${failedCount} 个` : ''}
          </p>
        </div>
        <button
          type="button"
          disabled={hasUploadActivity}
          onClick={onClear}
          className="text-sm text-slate-500 transition-colors hover:text-red-500 disabled:cursor-not-allowed disabled:opacity-50 dark:text-slate-400"
        >
          清空列表
        </button>
      </div>

      <div className="divide-y divide-slate-100 dark:divide-slate-700">
        {items.map(item => {
          const canEdit = item.status === 'READY' || item.status === 'UPLOAD_FAILED';
          const retryCoolingDown = Boolean(item.retryAvailableAt);
          return (
            <div key={item.clientId} className="p-5">
              <div className="flex flex-col gap-4 md:flex-row md:items-start">
                <div className="flex min-w-0 flex-1 items-start gap-3">
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary-50 text-primary-500 dark:bg-primary-900/30">
                    <FileText className="h-5 w-5" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="max-w-full truncate font-medium text-slate-900 dark:text-white">
                        {item.file.name}
                      </p>
                      {item.duplicate && (
                        <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-500 dark:bg-slate-700 dark:text-slate-300">
                          已存在
                        </span>
                      )}
                    </div>
                    <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                      {formatFileSize(item.file.size)}
                    </p>
                    <input
                      type="text"
                      value={item.customName}
                      disabled={!canEdit}
                      onChange={event => onNameChange(item.clientId, event.target.value)}
                      placeholder="知识库名称（可选，默认使用文件名）"
                      className="mt-3 w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 outline-none transition focus:border-primary-400 focus:ring-2 focus:ring-primary-100 disabled:bg-slate-50 disabled:text-slate-500 dark:border-slate-600 dark:bg-slate-700 dark:text-white dark:focus:ring-primary-900/40 dark:disabled:bg-slate-800"
                    />
                  </div>
                </div>

                <div className="flex shrink-0 items-center justify-between gap-2 md:justify-end">
                  <StatusBadge status={item.status} />
                  {item.status === 'UPLOAD_FAILED' && (
                    <button
                      type="button"
                      disabled={retryCoolingDown}
                      onClick={() => onRetryUpload(item.clientId)}
                      className="rounded-lg p-2 text-slate-400 transition hover:bg-primary-50 hover:text-primary-500 disabled:cursor-not-allowed disabled:opacity-50 dark:hover:bg-primary-900/30"
                      title={retryCoolingDown ? '2 秒后可重试' : '重新上传'}
                    >
                      <RefreshCw className="h-4 w-4" />
                    </button>
                  )}
                  {item.status === 'VECTOR_FAILED' && item.knowledgeBaseId && (
                    <button
                      type="button"
                      disabled={revectorizingId !== null}
                      onClick={() => onRevectorize(item.clientId)}
                      className="rounded-lg p-2 text-slate-400 transition hover:bg-primary-50 hover:text-primary-500 disabled:opacity-50 dark:hover:bg-primary-900/30"
                      title="重新向量化"
                    >
                      <RefreshCw className={`h-4 w-4 ${
                        revectorizingId === item.knowledgeBaseId ? 'animate-spin' : ''
                      }`} />
                    </button>
                  )}
                  {canEdit && (
                    <button
                      type="button"
                      onClick={() => onRemove(item.clientId)}
                      className="rounded-lg p-2 text-slate-400 transition hover:bg-red-50 hover:text-red-500 dark:hover:bg-red-900/30"
                      title="移除文件"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  )}
                </div>
              </div>

              {item.error && (
                <div className="ml-0 mt-3 flex items-start gap-2 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-300 md:ml-[52px]">
                  <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
                  <span className="break-words">{item.error}</span>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
}
