import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import {
  Calendar,
  ChevronRight,
  Clock,
  Filter,
  Loader2,
  Plus,
  Search,
  Trash2,
  X,
} from 'lucide-react';
import { reviewApi } from '../api/review';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import UploadReviewDialog from '../components/UploadReviewDialog';
import { formatDateOnly } from '../utils/date';
import type { ReviewListItemDTO } from '../types/review';

export default function ReviewListPage() {
  const navigate = useNavigate();
  const [reviews, setReviews] = useState<ReviewListItemDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [filterCompanyName, setFilterCompanyName] = useState('');
  const [filterStartDate, setFilterStartDate] = useState('');
  const [filterEndDate, setFilterEndDate] = useState('');
  const [showFilters, setShowFilters] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<{ id: number; name: string } | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [uploadDialogOpen, setUploadDialogOpen] = useState(false);

  const loadReviews = useCallback(async () => {
    setLoading(true);
    try {
      const data = await reviewApi.list({
        companyName: filterCompanyName || undefined,
        startDate: filterStartDate || undefined,
        endDate: filterEndDate || undefined,
      });
      setReviews(data);
    } catch (err) {
      console.error('加载复盘列表失败', err);
    } finally {
      setLoading(false);
    }
  }, [filterCompanyName, filterStartDate, filterEndDate]);

  useEffect(() => {
    loadReviews();
  }, [loadReviews]);

  // Search is handled client-side via API
  const handleSearch = useCallback(async () => {
    if (!searchQuery.trim()) {
      loadReviews();
      return;
    }
    setLoading(true);
    try {
      const data = await reviewApi.search(searchQuery.trim());
      setReviews(data);
    } catch {
      console.error('搜索失败');
    } finally {
      setLoading(false);
    }
  }, [searchQuery, loadReviews]);

  const handleDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await reviewApi.delete(deleteTarget.id);
      setDeleteTarget(null);
      await loadReviews();
    } catch (err) {
      alert(err instanceof Error ? err.message : '删除失败');
    } finally {
      setDeleting(false);
    }
  };

  const handleUploadSuccess = (reviewId: number) => {
    setUploadDialogOpen(false);
    navigate(`/reviews/${reviewId}`);
  };

  const clearFilters = () => {
    setFilterCompanyName('');
    setFilterStartDate('');
    setFilterEndDate('');
  };

  const hasActiveFilters = filterCompanyName || filterStartDate || filterEndDate;

  return (
    <div className="max-w-7xl mx-auto">
      {/* Header */}
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold text-slate-900 dark:text-white tracking-tight">
            面试复盘
          </h1>
          <p className="text-slate-500 dark:text-slate-400 mt-1">
            上传面试记录，AI 分析问题分类与项目经历
          </p>
        </div>
        <motion.button
          onClick={() => setUploadDialogOpen(true)}
          className="flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-primary-500 to-primary-600 text-white rounded-xl font-semibold shadow-lg shadow-primary-500/30 hover:shadow-xl transition-all"
          whileHover={{ scale: 1.02 }}
          whileTap={{ scale: 0.98 }}
        >
          <Plus className="w-5 h-5" />
          新建复盘
        </motion.button>
      </div>

      {/* Search & Filter Bar */}
      <div className="mb-6 space-y-3">
        <div className="flex gap-3">
          <div className="flex-1 relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-slate-400" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
              placeholder="搜索复盘记录..."
              className="w-full pl-10 pr-4 py-3 border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent bg-white dark:bg-slate-800 text-slate-900 dark:text-white placeholder-slate-400 text-sm"
            />
          </div>
          <button
            onClick={() => setShowFilters(!showFilters)}
            className={`flex items-center gap-2 px-4 py-3 rounded-xl border text-sm font-medium transition-colors
              ${showFilters
                ? 'border-primary-300 dark:border-primary-700 bg-primary-50 dark:bg-primary-900/20 text-primary-600 dark:text-primary-400'
                : 'border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700'
              }`}
          >
            <Filter className="w-4 h-4" />
            筛选
            {hasActiveFilters && (
              <span className="w-2 h-2 bg-primary-500 rounded-full" />
            )}
          </button>
        </div>

        {/* Filter controls */}
        <AnimatePresence>
          {showFilters && (
            <motion.div
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: 'auto' }}
              exit={{ opacity: 0, height: 0 }}
              className="overflow-hidden"
            >
              <div className="flex items-end gap-3 bg-white dark:bg-slate-800 rounded-xl p-4 border border-slate-200 dark:border-slate-600">
                <div className="flex-1">
                  <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">
                    公司名称
                  </label>
                  <input
                    type="text"
                    value={filterCompanyName}
                    onChange={(e) => setFilterCompanyName(e.target.value)}
                    placeholder="输入公司名"
                    className="w-full px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 text-sm bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">
                    开始日期
                  </label>
                  <input
                    type="date"
                    value={filterStartDate}
                    onChange={(e) => setFilterStartDate(e.target.value)}
                    className="px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 text-sm bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
                  />
                </div>
                <div>
                  <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">
                    结束日期
                  </label>
                  <input
                    type="date"
                    value={filterEndDate}
                    onChange={(e) => setFilterEndDate(e.target.value)}
                    className="px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 text-sm bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
                  />
                </div>
                {hasActiveFilters && (
                  <button
                    onClick={clearFilters}
                    className="flex items-center gap-1 px-3 py-2 text-sm text-slate-500 hover:text-slate-700 dark:hover:text-slate-300 transition-colors"
                  >
                    <X className="w-3 h-3" />
                    清除
                  </button>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>

      {/* Content */}
      {loading ? (
        <div className="flex items-center justify-center min-h-[40vh]">
          <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
        </div>
      ) : reviews.length === 0 ? (
        <div className="text-center py-16">
          <div className="w-20 h-20 mx-auto bg-slate-100 dark:bg-slate-700 rounded-2xl flex items-center justify-center mb-4">
            <Search className="w-10 h-10 text-slate-300 dark:text-slate-500" />
          </div>
          <p className="text-slate-500 dark:text-slate-400 text-lg font-medium">暂无复盘记录</p>
          <p className="text-slate-400 dark:text-slate-500 text-sm mt-1">
            点击右上角"新建复盘"开始记录面试
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          <AnimatePresence mode="popLayout">
            {reviews.map((review) => (
              <motion.div
                key={review.id}
                layout
                initial={{ opacity: 0, y: 20 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95 }}
                onClick={() => navigate(`/reviews/${review.id}`)}
                className="bg-white dark:bg-slate-800 rounded-2xl border border-slate-100 dark:border-slate-700 p-5 cursor-pointer hover:shadow-lg hover:border-primary-200 dark:hover:border-primary-800 transition-all group"
              >
                {/* Company name */}
                <h3 className="text-lg font-bold text-slate-900 dark:text-white mb-1 group-hover:text-primary-600 dark:group-hover:text-primary-400 transition-colors">
                  {review.companyName}
                </h3>

                {/* Position + round */}
                <p className="text-sm text-slate-500 dark:text-slate-400 mb-3">
                  {review.position} · 第{review.roundNumber}轮
                </p>

                {/* Date */}
                <div className="flex items-center gap-1.5 text-xs text-slate-400 dark:text-slate-500 mb-4">
                  <Calendar className="w-3.5 h-3.5" />
                  {formatDateOnly(review.interviewDate)}
                </div>

                {/* Status badges */}
                <div className="flex gap-2 flex-wrap">
                  <StatusBadge label="问题分析" done={review.questionAnalysisDone} />
                  <StatusBadge label="项目沉淀" done={review.projectAnalysisDone} />
                  <StatusBadge label="反问记录" done={review.questionRecordDone} />
                </div>

                {/* Actions */}
                <div className="flex items-center justify-between mt-4 pt-3 border-t border-slate-100 dark:border-slate-700">
                  <span className="text-xs text-slate-400 dark:text-slate-500 flex items-center gap-1">
                    <Clock className="w-3 h-3" />
                    {formatDateOnly(review.createdAt)}
                  </span>
                  <div className="flex items-center gap-1">
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        setDeleteTarget({ id: review.id, name: `${review.companyName} - ${review.position}` });
                      }}
                      className="p-1.5 text-slate-300 dark:text-slate-600 hover:text-red-500 dark:hover:text-red-400 rounded-lg transition-colors"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                    <ChevronRight className="w-4 h-4 text-slate-300 dark:text-slate-600 group-hover:text-primary-500 transition-colors" />
                  </div>
                </div>
              </motion.div>
            ))}
          </AnimatePresence>
        </div>
      )}

      {/* Delete dialog */}
      <DeleteConfirmDialog
        open={deleteTarget !== null}
        item={deleteTarget ? { id: deleteTarget.id, name: deleteTarget.name } : null}
        itemType="复盘记录"
        loading={deleting}
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />

      {/* Upload dialog */}
      <UploadReviewDialog
        open={uploadDialogOpen}
        onClose={() => setUploadDialogOpen(false)}
        onSuccess={handleUploadSuccess}
      />
    </div>
  );
}

function StatusBadge({ label, done }: { label: string; done: boolean }) {
  return (
    <span
      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium
        ${done
          ? 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400'
          : 'bg-slate-100 dark:bg-slate-700 text-slate-400 dark:text-slate-500'
        }`}
    >
      <span
        className={`w-1.5 h-1.5 rounded-full ${done ? 'bg-emerald-500' : 'bg-slate-300 dark:bg-slate-500'}`}
      />
      {label}
    </span>
  );
}
