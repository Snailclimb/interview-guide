// frontend/src/hooks/useInterviewSchedule.ts

import { useState, useEffect, useCallback } from 'react';
import { interviewScheduleApi } from '../api/interviewSchedule';
import type {
  InterviewSchedule,
  CreateInterviewRequest,
  InterviewStatus
} from '../types/interviewSchedule';

export function useInterviewSchedule() {
  const [interviews, setInterviews] = useState<InterviewSchedule[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchInterviews = useCallback(async (params?: {
    status?: string;
    start?: string;
    end?: string;
  }) => {
    setLoading(true);
    setError(null);
    try {
      const data = await interviewScheduleApi.getAll(params);
      setInterviews(data);
    } catch (err: any) {
      setError(err.message || '获取面试列表失败');
      console.error('Failed to fetch interviews:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  const createInterview = async (data: CreateInterviewRequest): Promise<InterviewSchedule> => {
    const newInterview = await interviewScheduleApi.create(data);
    await fetchInterviews();
    return newInterview;
  };

  const updateInterview = async (id: number, data: CreateInterviewRequest): Promise<InterviewSchedule> => {
    const updated = await interviewScheduleApi.update(id, data);
    await fetchInterviews();
    return updated;
  };

  const deleteInterview = async (id: number): Promise<void> => {
    await interviewScheduleApi.delete(id);
    setInterviews(interviews.filter(i => i.id !== id));
  };

  const updateStatus = async (id: number, status: InterviewStatus): Promise<InterviewSchedule> => {
    const updated = await interviewScheduleApi.updateStatus(id, status);
    await fetchInterviews();
    return updated;
  };

  useEffect(() => {
    fetchInterviews();
  }, [fetchInterviews]);

  return {
    interviews,
    loading,
    error,
    fetchInterviews,
    createInterview,
    updateInterview,
    deleteInterview,
    updateStatus,
  };
}
