import {ChangeEvent, DragEvent, useCallback, useState} from 'react';
import {AnimatePresence, motion} from 'framer-motion';
import {resumeApi} from '../api/resume';
import {getErrorMessage} from '../api/request';
import type {ResumeAnalysisResponse, StorageInfo} from '../types/resume';

interface UploadPageProps {
  onAnalysisComplete: (result: ResumeAnalysisResponse, storage: StorageInfo) => void;
}

type UploadState = 'idle' | 'uploading' | 'error';

export default function UploadPage({ onAnalysisComplete }: UploadPageProps) {
  const [state, setState] = useState<UploadState>('idle');
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [error, setError] = useState('');

  const handleDragOver = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(true);
  }, []);

  const handleDragLeave = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(false);
  }, []);

  const handleDrop = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const files = e.dataTransfer.files;
    if (files.length > 0) {
      setSelectedFile(files[0]);
      setError('');
    }
  }, []);

  const handleFileChange = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (files && files.length > 0) {
      setSelectedFile(files[0]);
      setError('');
    }
  }, []);

  const handleUpload = async () => {
    if (!selectedFile) return;
    setState('uploading');
    setError('');

    try {
      const data = await resumeApi.uploadAndAnalyze(selectedFile);
      
      // 检查分析结果是否有效
      if (!data.analysis || !data.storage) {
        throw new Error('分析结果不完整，请重试');
      }
      
      // 检查是否有分析错误（总分异常低或缺少必要字段）
      if (data.analysis.overallScore < 0 || !data.analysis.summary) {
        throw new Error('简历分析失败，请重试');
      }
      
      onAnalysisComplete(data.analysis, data.storage);
    } catch (err) {
      setError(getErrorMessage(err));
      setState('error');
    }
  };

  const formatFileSize = (bytes: number): string => {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  };

  return (
    <motion.div 
      className="max-w-3xl mx-auto pt-16"
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.5 }}
    >
      {/* 标题 */}
      <div className="text-center mb-12">
        <motion.h1 
          className="text-4xl md:text-5xl font-bold text-slate-900 mb-4 tracking-tight"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1 }}
        >
          开始您的 AI 模拟面试
        </motion.h1>
        <motion.p 
          className="text-lg text-slate-500"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.2 }}
        >
          上传 PDF 或 Word 简历，AI 将为您定制专属面试方案
        </motion.p>
      </div>

      {/* 上传区域 */}
      <motion.div
        className={`relative bg-white rounded-2xl p-12 cursor-pointer transition-all duration-300
          ${dragOver ? 'scale-[1.02] shadow-xl' : 'shadow-lg hover:shadow-xl'}`}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => document.getElementById('fileInput')?.click()}
        whileHover={{ scale: 1.01 }}
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.3 }}
      >
        {/* 渐变边框效果 */}
        <div className={`absolute inset-0 rounded-2xl p-[2px] bg-gradient-to-r from-indigo-200 via-purple-200 to-indigo-200 -z-10
          ${dragOver ? 'from-indigo-400 via-purple-400 to-indigo-400' : ''}`}>
          <div className="w-full h-full bg-white rounded-2xl" />
        </div>

        <div className="text-center">
          {/* 上传图标 */}
          <motion.div 
            className={`w-20 h-20 mx-auto mb-6 rounded-2xl flex items-center justify-center transition-colors
              ${dragOver ? 'bg-primary-100 text-primary-600' : 'bg-slate-100 text-slate-400'}`}
            animate={{ y: dragOver ? -5 : 0 }}
          >
            <svg className="w-10 h-10" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M21 15V19C21 19.5304 20.7893 20.0391 20.4142 20.4142C20.0391 20.7893 19.5304 21 19 21H5C4.46957 21 3.96086 20.7893 3.58579 20.4142C3.21071 20.0391 3 19.5304 3 19V15" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
              <polyline points="17,8 12,3 7,8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
              <line x1="12" y1="3" x2="12" y2="15" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
            </svg>
          </motion.div>

          <AnimatePresence mode="wait">
            {selectedFile ? (
              <motion.div
                key="file-selected"
                initial={{ opacity: 0, scale: 0.9 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.9 }}
                className="flex items-center justify-center gap-4 bg-slate-50 px-6 py-4 rounded-xl mb-6 max-w-md mx-auto"
              >
                <span className="text-3xl">📄</span>
                <div className="text-left flex-1 min-w-0">
                  <p className="font-semibold text-slate-900 truncate">{selectedFile.name}</p>
                  <p className="text-sm text-slate-500">{formatFileSize(selectedFile.size)}</p>
                </div>
                <button 
                  className="w-8 h-8 bg-red-100 text-red-500 rounded-lg hover:bg-red-200 transition-colors flex items-center justify-center"
                  onClick={(e) => { e.stopPropagation(); setSelectedFile(null); }}
                >
                  ✕
                </button>
              </motion.div>
            ) : (
              <motion.div
                key="no-file"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
              >
                <h3 className="text-xl font-semibold text-slate-900 mb-2">点击或拖拽文件至此处</h3>
                <p className="text-slate-400 mb-6">支持 PDF, DOCX, TXT (最大 10MB)</p>
              </motion.div>
            )}
          </AnimatePresence>

          <input
            type="file"
            id="fileInput"
            accept=".pdf,.doc,.docx,.txt"
            onChange={handleFileChange}
            className="hidden"
          />

          <motion.button
            className="bg-gradient-to-r from-primary-500 to-primary-600 text-white px-8 py-3.5 rounded-xl font-semibold shadow-lg shadow-primary-500/30 hover:shadow-xl hover:shadow-primary-500/40 transition-all"
            whileHover={{ scale: 1.02, y: -2 }}
            whileTap={{ scale: 0.98 }}
            onClick={(e) => { e.stopPropagation(); document.getElementById('fileInput')?.click(); }}
          >
            选择简历文件
          </motion.button>
        </div>
      </motion.div>

      {/* 错误提示 */}
      <AnimatePresence>
        {error && (
          <motion.div
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            className="mt-6 p-4 bg-red-50 border border-red-200 rounded-xl text-red-600 text-center"
          >
            ⚠️ {error}
          </motion.div>
        )}
      </AnimatePresence>

      {/* 开始分析按钮 */}
      <AnimatePresence>
        {selectedFile && state !== 'uploading' && (
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 20 }}
            className="mt-8 text-center"
          >
            <motion.button
              className="inline-flex items-center gap-3 bg-gradient-to-r from-emerald-500 to-emerald-600 text-white px-10 py-4 rounded-xl font-semibold text-lg shadow-lg shadow-emerald-500/30 hover:shadow-xl hover:shadow-emerald-500/40 transition-all"
              whileHover={{ scale: 1.02, y: -2 }}
              whileTap={{ scale: 0.98 }}
              onClick={handleUpload}
            >
              <svg className="w-6 h-6" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                <polyline points="22,4 12,14.01 9,11.01" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
              开始 AI 分析
            </motion.button>
          </motion.div>
        )}
      </AnimatePresence>

      {/* 加载状态 */}
      <AnimatePresence>
        {state === 'uploading' && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="mt-10 text-center"
          >
            <motion.div 
              className="w-12 h-12 border-4 border-slate-200 border-t-primary-500 rounded-full mx-auto mb-4"
              animate={{ rotate: 360 }}
              transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
            />
            <p className="text-slate-600 font-medium">AI 正在分析您的简历，请稍候...</p>
            <p className="text-sm text-slate-400 mt-2">首次分析可能需要 30 秒左右</p>
          </motion.div>
        )}
      </AnimatePresence>

      {/* 错误提示 */}
      <AnimatePresence>
        {error && state === 'error' && (
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 20 }}
            className="mt-8"
          >
            <div className="bg-red-50 border-2 border-red-200 rounded-xl p-6">
              <div className="flex items-start gap-4">
                <div className="flex-shrink-0">
                  <svg className="w-6 h-6 text-red-500" viewBox="0 0 24 24" fill="none">
                    <path d="M12 22C17.5228 22 22 17.5228 22 12C22 6.47715 17.5228 2 12 2C6.47715 2 2 6.47715 2 12C2 17.5228 6.47715 22 12 22Z" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                    <path d="M12 8V12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                    <path d="M12 16H12.01" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
                  </svg>
                </div>
                <div className="flex-1">
                  <h4 className="text-red-800 font-semibold mb-1">分析失败</h4>
                  <p className="text-red-600 text-sm">{error}</p>
                </div>
              </div>
              <div className="mt-4 flex gap-3">
                <button
                  onClick={() => {
                    setError('');
                    setState('idle');
                    handleUpload();
                  }}
                  className="px-4 py-2 bg-red-500 text-white rounded-lg hover:bg-red-600 transition-colors font-medium text-sm"
                >
                  重新尝试
                </button>
                <button
                  onClick={() => {
                    setError('');
                    setState('idle');
                    setSelectedFile(null);
                  }}
                  className="px-4 py-2 bg-white text-red-600 border border-red-200 rounded-lg hover:bg-red-50 transition-colors font-medium text-sm"
                >
                  重新上传
                </button>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </motion.div>
  );
}
