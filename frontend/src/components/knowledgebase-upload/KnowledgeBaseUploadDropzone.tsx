import { useState, type ChangeEvent, type DragEvent } from 'react';
import { AlertCircle, Upload } from 'lucide-react';

interface KnowledgeBaseUploadDropzoneProps {
  full: boolean;
  notice: string;
  onFilesSelected: (files: FileList) => void;
}

export default function KnowledgeBaseUploadDropzone({
  full,
  notice,
  onFilesSelected,
}: KnowledgeBaseUploadDropzoneProps) {
  const [dragOver, setDragOver] = useState(false);

  const handleFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    if (event.target.files) {
      onFilesSelected(event.target.files);
    }
    event.target.value = '';
  };

  const handleDrop = (event: DragEvent<HTMLLabelElement>) => {
    event.preventDefault();
    setDragOver(false);
    if (!full) {
      onFilesSelected(event.dataTransfer.files);
    }
  };

  return (
    <>
      <label
        htmlFor="knowledge-base-files"
        className={`block rounded-2xl border-2 border-dashed p-10 text-center transition-colors ${
          full
            ? 'cursor-not-allowed border-slate-200 bg-slate-50 opacity-70 dark:border-slate-700 dark:bg-slate-800/60'
            : dragOver
              ? 'cursor-pointer border-primary-500 bg-primary-50 dark:bg-primary-900/20'
              : 'cursor-pointer border-slate-300 bg-white hover:border-primary-400 dark:border-slate-600 dark:bg-slate-800'
        }`}
        onDragOver={(event) => {
          event.preventDefault();
          if (!full) setDragOver(true);
        }}
        onDragLeave={(event) => {
          event.preventDefault();
          setDragOver(false);
        }}
        onDrop={handleDrop}
      >
        <input
          id="knowledge-base-files"
          className="hidden"
          type="file"
          accept=".pdf,.doc,.docx,.txt,.md"
          multiple
          disabled={full}
          onChange={handleFileChange}
        />
        <Upload className={`mx-auto mb-4 h-12 w-12 ${dragOver ? 'text-primary-500' : 'text-slate-400'}`} />
        <p className="mb-2 text-lg font-semibold text-slate-800 dark:text-slate-100">
          点击选择或拖拽多个文件到这里
        </p>
        <p className="text-sm text-slate-500 dark:text-slate-400">
          支持 PDF、DOCX、DOC、TXT、MD，单个文件最大 50MB；上传期间仍可继续添加
        </p>
      </label>

      {notice && (
        <div className="mt-4 flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-700 dark:border-amber-800 dark:bg-amber-900/20 dark:text-amber-300">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          <span className="break-words">{notice}</span>
        </div>
      )}
    </>
  );
}
