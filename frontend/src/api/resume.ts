import { request } from './request';
import type { ResumeListItem, UploadResponse } from '../types/resume';

export const resumeApi = {
  /**
   * 上传简历并获取分析结果
   */
  async uploadAndAnalyze(file: File): Promise<UploadResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return request.upload<UploadResponse>('/api/resumes/upload', formData);
  },

  /**
   * 上传优化后的简历新版本（挂到同一版本族，旧版本保留，可对比新旧评分）
   */
  async uploadVersion(resumeId: number, file: File, note?: string): Promise<UploadResponse> {
    const formData = new FormData();
    formData.append('file', file);
    if (note && note.trim()) {
      formData.append('note', note.trim());
    }
    return request.upload<UploadResponse>(`/api/resumes/${resumeId}/versions`, formData);
  },

  /**
   * 获取所有简历列表
   */
  async getAllResumes(): Promise<ResumeListItem[]> {
    return request.get<ResumeListItem[]>('/api/resumes');
  },

  /**
   * 健康检查
   */
  async healthCheck(): Promise<{ status: string; service: string }> {
    return request.get('/api/resumes/health');
  },
};
