import { useEffect, useState, useRef, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, RefreshCw } from 'lucide-react';
import { EvaluationStatusResponse, VoiceEvaluationDetail, voiceInterviewApi } from '../api/voiceInterview';
import InterviewDetailPanel from '../components/InterviewDetailPanel';
import type { InterviewDetail } from '../api/history';

export default function VoiceInterviewEvaluationPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const [evaluation, setEvaluation] = useState<VoiceEvaluationDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [evaluateStatus, setEvaluateStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const pollingRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    loadEvaluation();
    return () => {
      if (pollingRef.current) {
        clearTimeout(pollingRef.current);
      }
    };
  }, [sessionId]);

  const loadEvaluation = async () => {
    if (!sessionId) return;

    setLoading(true);
    setError(null);

    try {
      const status = await voiceInterviewApi.getEvaluation(parseInt(sessionId));
      handleStatusResponse(status);
    } catch {
      try {
        const status = await voiceInterviewApi.generateEvaluation(parseInt(sessionId));
        handleStatusResponse(status);
      } catch (err) {
        console.error('Failed to trigger evaluation:', err);
        setError('触发评估失败，请重试');
        setLoading(false);
      }
    }
  };

  const handleStatusResponse = (response: EvaluationStatusResponse) => {
    const status = response.evaluateStatus;
    setEvaluateStatus(status);

    if (status === 'COMPLETED' && response.evaluation) {
      setEvaluation(response.evaluation);
      setLoading(false);
    } else if (status === 'FAILED') {
      setError(response.evaluateError || '评估生成失败');
      setLoading(false);
    } else {
      startPolling();
    }
  };

  const startPolling = useCallback(() => {
    if (pollingRef.current) {
      clearTimeout(pollingRef.current);
    }

    pollingRef.current = setTimeout(async () => {
      if (!sessionId) return;

      try {
        const response = await voiceInterviewApi.getEvaluation(parseInt(sessionId));
        const status = response.evaluateStatus;
        setEvaluateStatus(status);

        if (status === 'COMPLETED' && response.evaluation) {
          setEvaluation(response.evaluation);
          setLoading(false);
        } else if (status === 'FAILED') {
          setError(response.evaluateError || '评估生成失败');
          setLoading(false);
        } else {
          startPolling();
        }
      } catch {
        setError('获取评估状态失败');
        setLoading(false);
      }
    }, 3000);
  }, [sessionId]);

  const handleRetry = async () => {
    if (!sessionId) return;
    setLoading(true);
    setError(null);
    setEvaluateStatus(null);

    try {
      const status = await voiceInterviewApi.generateEvaluation(parseInt(sessionId));
      handleStatusResponse(status);
    } catch (err) {
      console.error('Failed to retry evaluation:', err);
      setError('重试失败，请稍后再试');
      setLoading(false);
    }
  };

  const interviewDetail = useMemo<InterviewDetail | null>(() => {
    if (!evaluation) return null;
    return {
      id: 0,
      sessionId: sessionId!,
      totalQuestions: evaluation.totalQuestions,
      status: 'COMPLETED',
      overallScore: evaluation.overallScore,
      overallFeedback: evaluation.overallFeedback,
      createdAt: '',
      completedAt: '',
      strengths: evaluation.strengths,
      improvements: evaluation.improvements,
      answers: evaluation.answers.map(a => ({
        questionIndex: a.questionIndex,
        question: a.question,
        category: a.category,
        userAnswer: a.userAnswer,
        score: a.score,
        feedback: a.feedback,
        referenceAnswer: a.referenceAnswer ?? undefined,
        keyPoints: a.keyPoints ?? undefined,
        answeredAt: '',
      })),
    };
  }, [evaluation, sessionId]);

  // Loading state
  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <div className="text-center">
          <div className="w-10 h-10 border-3 border-slate-200 dark:border-slate-700 border-t-primary-500 rounded-full animate-spin mx-auto mb-4" />
          <p className="text-slate-600 dark:text-slate-300">
            {evaluateStatus === 'PROCESSING' ? 'AI 正在分析面试表现...' : '正在生成评估报告...'}
          </p>
          <p className="text-slate-400 text-sm mt-2">预计需要 10-30 秒</p>
        </div>
      </div>
    );
  }

  // Error state
  if (error && !evaluation) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <div className="text-center">
          <p className="text-slate-600 dark:text-slate-300 text-lg mb-2">评估报告生成失败</p>
          <p className="text-slate-400 text-sm mb-6">{error}</p>
          <div className="flex items-center gap-3 justify-center">
            <button
              onClick={handleRetry}
              className="px-6 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600 flex items-center gap-2"
            >
              <RefreshCw className="w-4 h-4" />
              重试
            </button>
            <button
              onClick={() => navigate('/interviews')}
              className="px-6 py-2 bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-300 rounded-lg hover:bg-slate-300 dark:hover:bg-slate-600"
            >
              返回列表
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (!evaluation || !interviewDetail) {
    return null;
  }

  return (
    <div className="pb-10">
      <div className="max-w-6xl mx-auto">
        <div className="flex items-center gap-3 mb-6">
          <button
            onClick={() => navigate('/interviews')}
            className="p-2 text-slate-400 hover:text-slate-600 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg transition-colors"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <div>
            <h1 className="text-xl font-bold text-slate-900 dark:text-white">面试评估报告</h1>
            <p className="text-sm text-slate-500 dark:text-slate-400">语音会话 ID: {sessionId}</p>
          </div>
        </div>
        <InterviewDetailPanel interview={interviewDetail} />
      </div>
    </div>
  );
}
