import { AlertCircle, ArrowLeft, Upload } from 'lucide-react';

import KnowledgeBaseUploadDropzone from '../components/knowledgebase-upload/KnowledgeBaseUploadDropzone';
import KnowledgeBaseUploadList from '../components/knowledgebase-upload/KnowledgeBaseUploadList';
import { useKnowledgeBaseBatchUpload } from '../hooks/useKnowledgeBaseBatchUpload';
import { MAX_BATCH_FILES, MAX_CONCURRENT_UPLOADS } from './knowledgeBaseBatchUpload';

interface KnowledgeBaseUploadPageProps {
  onBack: () => void;
}

export default function KnowledgeBaseUploadPage({ onBack }: KnowledgeBaseUploadPageProps) {
  const batchUpload = useKnowledgeBaseBatchUpload();

  return (
    <div className="mx-auto max-w-5xl pb-16 pt-10">
      <div className="mb-8 text-center">
        <h1 className="mb-3 text-4xl font-bold tracking-tight text-slate-900 dark:text-white">
          批量上传知识库
        </h1>
        <p className="text-slate-500 dark:text-slate-400">
          单批最多 {MAX_BATCH_FILES} 个文件，同时上传 {MAX_CONCURRENT_UPLOADS} 个，上传后自动异步向量化
        </p>
      </div>

      <KnowledgeBaseUploadDropzone
        full={batchUpload.items.length >= MAX_BATCH_FILES}
        notice={batchUpload.selectionNotice}
        onFilesSelected={batchUpload.addFiles}
      />

      {batchUpload.pollError && (
        <div className="mt-4 flex items-center gap-2 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-700 dark:border-amber-800 dark:bg-amber-900/20 dark:text-amber-300">
          <AlertCircle className="h-4 w-4" />
          {batchUpload.pollError}
        </div>
      )}

      <KnowledgeBaseUploadList
        items={batchUpload.items}
        completedCount={batchUpload.completedCount}
        failedCount={batchUpload.failedCount}
        hasUploadActivity={batchUpload.hasUploadActivity}
        revectorizingId={batchUpload.revectorizingId}
        onClear={batchUpload.clearItems}
        onNameChange={batchUpload.updateCustomName}
        onRemove={batchUpload.removeItem}
        onRetryUpload={batchUpload.retryUpload}
        onRevectorize={clientId => void batchUpload.revectorize(clientId)}
      />

      <div className="mt-8 flex flex-wrap justify-center gap-4">
        <button
          type="button"
          onClick={onBack}
          disabled={batchUpload.hasUploadActivity}
          className="inline-flex items-center gap-2 rounded-xl border border-slate-200 px-6 py-3 font-medium text-slate-600 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
        >
          <ArrowLeft className="h-4 w-4" />
          返回知识库
        </button>
        {batchUpload.readyCount > 0 && (
          <button
            type="button"
            onClick={batchUpload.enqueueReadyItems}
            className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-emerald-500 to-emerald-600 px-8 py-3 font-semibold text-white shadow-lg shadow-emerald-500/30 transition hover:shadow-xl disabled:cursor-not-allowed disabled:opacity-60"
          >
            <Upload className="h-5 w-5" />
            加入上传队列 {batchUpload.readyCount} 个文件
          </button>
        )}
      </div>
    </div>
  );
}
