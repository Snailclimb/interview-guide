import {useRef, useState} from 'react';
import {AnimatePresence, motion} from 'framer-motion';
import {AlertCircle, FileText, GitBranch, Loader2, Upload, X} from 'lucide-react';

export interface VersionUploadModalProps {
  open: boolean;
  /** 当前版本号（用于提示"正在从 vX 创建新版本"） */
  currentVersionNo?: number;
  /** 当前版本文件名 */
  currentFilename?: string;
  uploading?: boolean;
  error?: string;
  onUpload: (file: File, note?: string) => void;
  onCancel: () => void;
}

/**
 * 上传优化后简历新版本的弹窗
 * 支持选择文件 + 填写版本说明，新版本将挂到当前简历的版本族。
 */
export default function VersionUploadModal({
  open,
  currentVersionNo,
  currentFilename,
  uploading = false,
  error,
  onUpload,
  onCancel,
}: VersionUploadModalProps) {
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [note, setNote] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  const formatFileSize = (bytes: number): string => {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  };

  const reset = () => {
    setSelectedFile(null);
    setNote('');
  };

  const handleCancel = () => {
    reset();
    onCancel();
  };

  const handleConfirm = () => {
    if (!selectedFile) return;
    onUpload(selectedFile, note.trim() || undefined);
  };

  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            initial={{opacity: 0}}
            animate={{opacity: 1}}
            exit={{opacity: 0}}
            onClick={handleCancel}
            className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
          />
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <motion.div
              initial={{opacity: 0, scale: 0.95, y: 20}}
              animate={{opacity: 1, scale: 1, y: 0}}
              exit={{opacity: 0, scale: 0.95, y: 20}}
              onClick={(e) => e.stopPropagation()}
              className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-lg w-full p-6"
            >
              {/* 标题 */}
              <div className="flex items-center gap-3 mb-1">
                <div className="w-10 h-10 bg-primary-100 dark:bg-primary-900/50 rounded-xl flex items-center justify-center">
                  <GitBranch className="w-5 h-5 text-primary-600 dark:text-primary-400"/>
                </div>
                <div>
                  <h3 className="text-xl font-bold text-slate-900 dark:text-white">上传优化后的新版本</h3>
                  <p className="text-sm text-slate-500 dark:text-slate-400">
                    {currentVersionNo !== undefined
                      ? `正在从 v${currentVersionNo} 创建新版本，旧版本与旧分析将全部保留`
                      : '将作为新版本上传，旧版本与旧分析将全部保留'}
                  </p>
                </div>
              </div>

              {/* 当前版本信息 */}
              {currentFilename && (
                <div className="mt-4 px-4 py-3 bg-slate-50 dark:bg-slate-700/50 rounded-xl flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300">
                  <FileText className="w-4 h-4 text-slate-400 shrink-0"/>
                  <span className="truncate">{currentFilename}</span>
                  {currentVersionNo !== undefined && (
                    <span className="ml-auto shrink-0 px-2 py-0.5 bg-primary-100 dark:bg-primary-900/60 text-primary-600 dark:text-primary-300 rounded-full text-xs font-semibold">
                      v{currentVersionNo}
                    </span>
                  )}
                </div>
              )}

              {/* 文件选择 */}
              <div className="mt-4">
                <label className="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">选择优化后的简历文件</label>
                <input
                  ref={inputRef}
                  type="file"
                  accept=".pdf,.doc,.docx,.txt,.md"
                  className="hidden"
                  onChange={(e) => {
                    const files = e.target.files;
                    if (files && files.length > 0) setSelectedFile(files[0]);
                  }}
                  disabled={uploading}
                />
                {selectedFile ? (
                  <div className="flex items-center gap-3 bg-slate-50 dark:bg-slate-700/50 px-4 py-3 rounded-xl">
                    <FileText className="w-5 h-5 text-primary-500 shrink-0"/>
                    <div className="min-w-0 flex-1">
                      <p className="font-medium text-slate-900 dark:text-white truncate">{selectedFile.name}</p>
                      <p className="text-xs text-slate-500 dark:text-slate-400">{formatFileSize(selectedFile.size)}</p>
                    </div>
                    <button
                      onClick={() => setSelectedFile(null)}
                      disabled={uploading}
                      className="w-8 h-8 bg-red-100 dark:bg-red-900/50 text-red-500 dark:text-red-400 rounded-lg hover:bg-red-200 dark:hover:bg-red-900/70 transition-colors flex items-center justify-center shrink-0"
                    >
                      <X className="w-4 h-4"/>
                    </button>
                  </div>
                ) : (
                  <button
                    onClick={() => inputRef.current?.click()}
                    disabled={uploading}
                    className="w-full py-6 border-2 border-dashed border-slate-200 dark:border-slate-600 rounded-xl flex flex-col items-center gap-2 text-slate-400 dark:text-slate-500 hover:border-primary-400 hover:text-primary-500 transition-colors"
                  >
                    <Upload className="w-6 h-6"/>
                    <span className="text-sm font-medium">点击选择文件（PDF, DOCX, TXT）</span>
                  </button>
                )}
              </div>

              {/* 版本说明 */}
              <div className="mt-4">
                <label className="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">
                  版本说明 <span className="text-slate-400 font-normal">（可选，如"根据建议优化了项目描述"）</span>
                </label>
                <textarea
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                  placeholder="记录这一版优化了什么，便于后续回顾"
                  rows={2}
                  disabled={uploading}
                  className="w-full px-4 py-3 border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500 resize-none"
                />
              </div>

              {/* 错误提示 */}
              <AnimatePresence>
                {error && (
                  <motion.div
                    initial={{opacity: 0, y: -10}}
                    animate={{opacity: 1, y: 0}}
                    exit={{opacity: 0, y: -10}}
                    className="mt-4 p-3 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 rounded-xl text-red-600 dark:text-red-400 text-sm flex items-center gap-2"
                  >
                    <AlertCircle className="w-4 h-4 shrink-0"/>
                    {error}
                  </motion.div>
                )}
              </AnimatePresence>

              {/* 按钮 */}
              <div className="flex gap-3 justify-end mt-6">
                <motion.button
                  onClick={handleCancel}
                  disabled={uploading}
                  className="px-5 py-2.5 border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 rounded-xl font-medium hover:bg-slate-50 dark:hover:bg-slate-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                  whileHover={{scale: 1.02}}
                  whileTap={{scale: 0.98}}
                >
                  取消
                </motion.button>
                <motion.button
                  onClick={handleConfirm}
                  disabled={uploading || !selectedFile}
                  className="px-5 py-2.5 bg-gradient-to-r from-primary-500 to-primary-600 text-white rounded-xl font-semibold shadow-lg disabled:opacity-50 disabled:cursor-not-allowed transition-all flex items-center gap-2"
                  whileHover={{scale: 1.02}}
                  whileTap={{scale: 0.98}}
                >
                  {uploading ? (
                    <>
                      <Loader2 className="w-4 h-4 animate-spin"/>
                      上传分析中...
                    </>
                  ) : (
                    '上传并分析'
                  )}
                </motion.button>
              </div>
            </motion.div>
          </div>
        </>
      )}
    </AnimatePresence>
  );
}
