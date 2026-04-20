import { useState, useEffect, useRef } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { AlertCircle, Loader2, Upload, X, FileText } from 'lucide-react';
import { interviewScheduleApi } from '../api/interviewSchedule';
import { reviewApi } from '../api/review';
import type { InterviewSchedule } from '../types/interviewSchedule';

interface UploadReviewDialogProps {
  open: boolean;
  onClose: () => void;
  onSuccess: (reviewId: number) => void;
}

export default function UploadReviewDialog({ open, onClose, onSuccess }: UploadReviewDialogProps) {
  const [schedules, setSchedules] = useState<InterviewSchedule[]>([]);
  const [loadingSchedules, setLoadingSchedules] = useState(false);
  const [selectedScheduleId, setSelectedScheduleId] = useState<number | null>(null);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) {
      setLoadingSchedules(true);
      interviewScheduleApi
        .getAll()
        .then((data) => setSchedules(data))
        .catch(() => setSchedules([]))
        .finally(() => setLoadingSchedules(false));
    }
  }, [open]);

  // Reset state when dialog opens
  useEffect(() => {
    if (open) {
      setSelectedScheduleId(null);
      setSelectedFile(null);
      setUploading(false);
      setError('');
    }
  }, [open]);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const validExtensions = ['.txt', '.md'];
    const ext = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();
    if (!validExtensions.includes(ext)) {
      setError('仅支持 .txt 和 .md 格式的文件');
      return;
    }

    if (file.size > 5 * 1024 * 1024) {
      setError('文件大小不能超过 5MB');
      return;
    }

    setError('');
    setSelectedFile(file);
  };

  const handleUpload = async () => {
    if (!selectedFile || !selectedScheduleId) return;

    setUploading(true);
    setError('');

    try {
      const result = await reviewApi.upload(selectedFile, selectedScheduleId);
      onSuccess(result.reviewId);
    } catch (err) {
      setError(err instanceof Error ? err.message : '上传失败，请重试');
    } finally {
      setUploading(false);
    }
  };

  const formatFileSize = (bytes: number): string => {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  };

  return (
    <AnimatePresence>
      {open && (
        <>
          {/* Backdrop */}
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
            className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
          />

          {/* Dialog */}
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              onClick={(e) => e.stopPropagation()}
              className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-lg w-full p-6"
            >
              {/* Header */}
              <div className="flex items-center justify-between mb-6">
                <h3 className="text-xl font-bold text-slate-900 dark:text-white">
                  新建面试复盘
                </h3>
                <button
                  onClick={onClose}
                  disabled={uploading}
                  className="p-2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg transition-colors"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>

              {/* Schedule selector */}
              <div className="mb-4">
                <label className="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">
                  关联面试日程
                </label>
                {loadingSchedules ? (
                  <div className="flex items-center gap-2 text-slate-400 py-2">
                    <Loader2 className="w-4 h-4 animate-spin" />
                    <span className="text-sm">加载日程...</span>
                  </div>
                ) : schedules.length === 0 ? (
                  <p className="text-sm text-slate-400 py-2">暂无面试日程，请先创建日程</p>
                ) : (
                  <select
                    value={selectedScheduleId ?? ''}
                    onChange={(e) => setSelectedScheduleId(Number(e.target.value) || null)}
                    disabled={uploading}
                    className="w-full px-4 py-3 border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent bg-white dark:bg-slate-700 text-slate-900 dark:text-white text-sm"
                  >
                    <option value="">请选择面试日程</option>
                    {schedules.map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.companyName} - {s.position} - 第{s.roundNumber}轮
                      </option>
                    ))}
                  </select>
                )}
              </div>

              {/* File upload */}
              <div className="mb-4">
                <label className="block text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">
                  面试记录文件
                </label>
                <div
                  onClick={() => !uploading && fileInputRef.current?.click()}
                  className={`border-2 border-dashed rounded-xl p-6 text-center cursor-pointer transition-colors
                    ${selectedFile
                      ? 'border-primary-300 dark:border-primary-700 bg-primary-50 dark:bg-primary-900/20'
                      : 'border-slate-200 dark:border-slate-600 hover:border-primary-300 dark:hover:border-primary-700'
                    }`}
                >
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept=".txt,.md"
                    onChange={handleFileChange}
                    className="hidden"
                    disabled={uploading}
                  />
                  {selectedFile ? (
                    <div className="flex items-center justify-center gap-3">
                      <FileText className="w-8 h-8 text-primary-500" />
                      <div className="text-left">
                        <p className="font-medium text-slate-900 dark:text-white text-sm">{selectedFile.name}</p>
                        <p className="text-xs text-slate-400">{formatFileSize(selectedFile.size)}</p>
                      </div>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          setSelectedFile(null);
                        }}
                        className="ml-2 p-1 text-slate-400 hover:text-red-500 rounded transition-colors"
                      >
                        <X className="w-4 h-4" />
                      </button>
                    </div>
                  ) : (
                    <div className="space-y-2">
                      <Upload className="w-8 h-8 mx-auto text-slate-300 dark:text-slate-500" />
                      <p className="text-sm text-slate-500 dark:text-slate-400">
                        点击选择文件（.txt 或 .md，最大 5MB）
                      </p>
                    </div>
                  )}
                </div>
              </div>

              {/* Error message */}
              <AnimatePresence>
                {error && (
                  <motion.div
                    initial={{ opacity: 0, y: -10 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, y: -10 }}
                    className="mb-4 p-3 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 rounded-xl text-red-600 dark:text-red-400 text-sm flex items-center gap-2"
                  >
                    <AlertCircle className="w-4 h-4 shrink-0" />
                    {error}
                  </motion.div>
                )}
              </AnimatePresence>

              {/* Buttons */}
              <div className="flex gap-3 justify-end">
                <button
                  onClick={onClose}
                  disabled={uploading}
                  className="px-5 py-2.5 border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 rounded-xl font-medium hover:bg-slate-50 dark:hover:bg-slate-700 transition-all disabled:opacity-50"
                >
                  取消
                </button>
                <motion.button
                  onClick={handleUpload}
                  disabled={uploading || !selectedFile || !selectedScheduleId}
                  className="px-5 py-2.5 bg-gradient-to-r from-primary-500 to-primary-600 text-white rounded-xl font-semibold shadow-lg shadow-primary-500/30 transition-all disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
                  whileHover={{ scale: uploading ? 1 : 1.02 }}
                  whileTap={{ scale: uploading ? 1 : 0.98 }}
                >
                  {uploading ? (
                    <>
                      <Loader2 className="w-4 h-4 animate-spin" />
                      上传中...
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
