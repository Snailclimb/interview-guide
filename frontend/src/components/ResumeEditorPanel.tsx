import {useEffect, useMemo, useState} from 'react';
import {motion} from 'framer-motion';
import {Check, Download, FileText, FileType, Loader2, Save, Sparkles, WandSparkles} from 'lucide-react';
import type {ResumeEditor, ResumeEditorContent} from '../api/history';

interface ResumeEditorPanelProps {
  editor: ResumeEditor | null;
  loading: boolean;
  saving: boolean;
  optimizing: boolean;
  applying: boolean;
  error?: string | null;
  onSave: (content: ResumeEditorContent) => Promise<void>;
  onOptimize: (content: ResumeEditorContent) => Promise<void>;
  onApply: () => Promise<void>;
  exporting?: 'pdf' | 'docx' | null;
  onExport: (format: 'pdf' | 'docx') => Promise<void>;
}

const emptyContent: ResumeEditorContent = {
  name: '', email: '', phone: '', location: '', targetPosition: '', skills: '',
  workExperience: '', projects: '', education: '', selfEvaluation: '', rawText: '',
};

function normalizeContent(content?: Partial<ResumeEditorContent> | null): ResumeEditorContent {
  return {
    name: content?.name ?? '',
    email: content?.email ?? '',
    phone: content?.phone ?? '',
    location: content?.location ?? '',
    targetPosition: content?.targetPosition ?? '',
    skills: content?.skills ?? '',
    workExperience: content?.workExperience ?? '',
    projects: content?.projects ?? '',
    education: content?.education ?? '',
    selfEvaluation: content?.selfEvaluation ?? '',
    rawText: content?.rawText ?? '',
  };
}

const fields: Array<{key: keyof ResumeEditorContent; label: string; placeholder: string; rows: number}> = [
  {key: 'skills', label: '专业技能', placeholder: '例如：Java、Spring Boot、MySQL、Redis……', rows: 5},
  {key: 'workExperience', label: '工作 / 实习经历', placeholder: '按时间倒序填写公司、职位、负责内容和成果。', rows: 7},
  {key: 'projects', label: '项目经历', placeholder: '建议描述项目背景、个人动作、技术方案和量化结果。', rows: 9},
  {key: 'education', label: '教育经历', placeholder: '学校、专业、学历、时间、成绩或相关荣誉。', rows: 4},
  {key: 'selfEvaluation', label: '个人评价', placeholder: '用事实概括你的优势和求职方向。', rows: 4},
];

export default function ResumeEditorPanel({
  editor,
  loading,
  saving,
  optimizing,
  applying,
  error,
  onSave,
  onOptimize,
  onApply,
  exporting = null,
  onExport,
}: ResumeEditorPanelProps) {
  const [content, setContent] = useState<ResumeEditorContent>(emptyContent);
  const [savedContent, setSavedContent] = useState<ResumeEditorContent>(emptyContent);

  useEffect(() => {
    const next = normalizeContent(editor?.content);
    setContent(next);
    setSavedContent(next);
  }, [editor]);

  const dirty = useMemo(() => JSON.stringify(content) !== JSON.stringify(savedContent), [content, savedContent]);
  const hasStructuredContent = fields.some(field => content[field.key].trim().length > 0)
    || ['name', 'email', 'phone', 'location', 'targetPosition'].some(key => content[key as keyof ResumeEditorContent].trim());

  const update = (key: keyof ResumeEditorContent, value: string) => {
    setContent(previous => ({...previous, [key]: value}));
  };

  const save = async () => {
    await onSave(content);
    setSavedContent(content);
  };

  const optimize = async () => {
    await onOptimize(content);
  };

  if (loading) {
    return (
      <div className="bg-white dark:bg-slate-800 rounded-2xl p-12 text-center">
        <Loader2 className="w-8 h-8 mx-auto mb-3 text-primary-500 animate-spin" />
        <p className="text-slate-500 dark:text-slate-400">正在加载可编辑简历…</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="bg-white dark:bg-slate-800 rounded-2xl p-6">
        <div className="flex flex-wrap items-start justify-between gap-4 mb-5">
          <div>
            <div className="flex items-center gap-2 text-slate-700 dark:text-slate-200">
              <FileText className="w-5 h-5 text-primary-500" />
              <h3 className="font-semibold">简历编辑与优化</h3>
            </div>
            <p className="text-sm text-slate-500 dark:text-slate-400 mt-2">
              AI 只生成草稿，保存后仍可手动修改；原始上传文件始终保留。
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <motion.button
              onClick={() => void onExport('pdf')}
              disabled={!editor || dirty || saving || optimizing || applying || exporting !== null}
              className="px-4 py-2 border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 rounded-lg text-sm font-medium hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50 flex items-center gap-2"
              whileTap={{scale: 0.98}}
            >
              {exporting === 'pdf' ? <Loader2 className="w-4 h-4 animate-spin" /> : <Download className="w-4 h-4" />}
              {exporting === 'pdf' ? '导出中…' : '导出 PDF'}
            </motion.button>
            <motion.button
              onClick={() => void onExport('docx')}
              disabled={!editor || dirty || saving || optimizing || applying || exporting !== null}
              className="px-4 py-2 border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 rounded-lg text-sm font-medium hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50 flex items-center gap-2"
              whileTap={{scale: 0.98}}
            >
              {exporting === 'docx' ? <Loader2 className="w-4 h-4 animate-spin" /> : <FileType className="w-4 h-4" />}
              {exporting === 'docx' ? '导出中…' : '导出 Word'}
            </motion.button>
            <motion.button
              onClick={optimize}
              disabled={saving || optimizing || applying}
              className="px-4 py-2 bg-gradient-to-r from-primary-500 to-primary-600 text-white rounded-lg text-sm font-medium hover:shadow-lg disabled:opacity-50 flex items-center gap-2"
              whileTap={{scale: 0.98}}
            >
              {optimizing ? <Loader2 className="w-4 h-4 animate-spin" /> : <WandSparkles className="w-4 h-4" />}
              {optimizing ? 'AI 优化中…' : 'AI 优化并生成草稿'}
            </motion.button>
            <motion.button
              onClick={save}
              disabled={!dirty || saving || optimizing || applying}
              className="px-4 py-2 border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 rounded-lg text-sm font-medium hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50 flex items-center gap-2"
              whileTap={{scale: 0.98}}
            >
              {saving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
              {saving ? '保存中…' : '保存编辑版'}
            </motion.button>
          </div>
        </div>

        {error && (
          <div className="mb-5 rounded-lg border border-red-200 bg-red-50 dark:bg-red-900/20 dark:border-red-800 px-4 py-3 text-sm text-red-600 dark:text-red-300">
            {error}
          </div>
        )}

        {editor?.analysisOutdated && (
          <div className="mb-5 rounded-lg border border-amber-200 bg-amber-50 dark:bg-amber-900/20 dark:border-amber-800 px-4 py-3 text-sm text-amber-700 dark:text-amber-300">
            当前编辑版已经应用到后续面试，但简历分析是旧版本结果；建议保存后重新分析。
          </div>
        )}

        <div className="rounded-xl border border-primary-100 dark:border-primary-900/50 bg-primary-50/60 dark:bg-primary-900/20 p-4 mb-5">
          <div className="flex items-start gap-3">
            <Sparkles className="w-5 h-5 text-primary-500 mt-0.5 shrink-0" />
            <div className="text-sm text-slate-600 dark:text-slate-300 leading-6">
              <p className="font-medium text-slate-800 dark:text-slate-100">建议使用方式</p>
              <p>先点击“AI 优化并生成草稿”，检查项目和基本信息，再手动修改并保存。只有点击“应用到后续面试”，编辑版才会成为后续模拟面试使用的简历。</p>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-6">
          {[
            ['name', '姓名', '你的姓名'],
            ['targetPosition', '求职意向', '例如：Java 后端开发'],
            ['email', '邮箱', 'name@example.com'],
            ['phone', '手机号', '联系电话'],
            ['location', '所在地', '例如：北京'],
          ].map(([key, label, placeholder]) => (
            <label key={key} className="block">
              <span className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">{label}</span>
              <input
                value={content[key as keyof ResumeEditorContent]}
                onChange={event => update(key as keyof ResumeEditorContent, event.target.value)}
                placeholder={placeholder}
                className="w-full rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 px-3 py-2.5 text-sm text-slate-800 dark:text-slate-100 outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
              />
            </label>
          ))}
        </div>

        <div className="space-y-5">
          {fields.map(field => (
            <label key={field.key} className="block">
              <span className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">{field.label}</span>
              <textarea
                value={content[field.key]}
                onChange={event => update(field.key, event.target.value)}
                placeholder={field.placeholder}
                rows={field.rows}
                className="w-full rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 px-3 py-2.5 text-sm leading-6 text-slate-800 dark:text-slate-100 outline-none resize-y focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
              />
            </label>
          ))}
        </div>

        {!hasStructuredContent && content.rawText && (
          <label className="block mt-5">
            <span className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">未结构化简历原文</span>
            <textarea
              value={content.rawText}
              onChange={event => update('rawText', event.target.value)}
              rows={14}
              className="w-full rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 px-3 py-2.5 text-sm leading-6 text-slate-800 dark:text-slate-100 outline-none resize-y focus:border-primary-500 focus:ring-2 focus:ring-primary-100"
            />
          </label>
        )}
      </div>

      <div className="bg-white dark:bg-slate-800 rounded-2xl p-6 flex flex-wrap items-center justify-between gap-4">
        <div>
          <p className="font-medium text-slate-800 dark:text-slate-100">应用到后续模拟面试</p>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">不会修改原始上传文件；应用后建议重新分析以获得新的评分。</p>
        </div>
        <button
          onClick={() => void onApply()}
          disabled={dirty || !editor || editor.source === 'ORIGINAL' || applying || saving || optimizing}
          className="px-4 py-2.5 rounded-lg bg-emerald-500 text-white text-sm font-medium hover:bg-emerald-600 disabled:opacity-50 flex items-center gap-2"
        >
          {applying ? <Loader2 className="w-4 h-4 animate-spin" /> : <Check className="w-4 h-4" />}
          {applying ? '应用中…' : dirty ? '请先保存编辑版' : '应用到后续面试'}
        </button>
      </div>
    </div>
  );
}
