import { request } from './request';
import type {
  ReviewListItemDTO,
  ReviewDetailDTO,
  ArtifactDTO,
  AnalyzeRequest,
} from '../types/review';

export const reviewApi = {
  /**
   * 上传面试记录
   */
  upload: (file: File, scheduleId: number): Promise<{ reviewId: number }> => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('scheduleId', scheduleId.toString());
    return request.upload<{ reviewId: number }>('/api/review/upload', formData);
  },

  /**
   * 触发分析
   */
  analyze: (reviewId: number, body: AnalyzeRequest): Promise<ArtifactDTO> => {
    return request.post<ArtifactDTO>(`/api/review/${reviewId}/analyze`, body, {
      timeout: 180000, // 3分钟，LLM 分析长转录稿需要较长时间
    });
  },

  /**
   * 手动编辑 artifact
   */
  updateArtifact: (reviewId: number, artifactId: number, content: string): Promise<ArtifactDTO> => {
    return request.put<ArtifactDTO>(`/api/review/${reviewId}/artifacts/${artifactId}`, { content });
  },

  /**
   * 列出复盘记录
   */
  list: (params?: { companyName?: string; startDate?: string; endDate?: string }): Promise<ReviewListItemDTO[]> => {
    const query = new URLSearchParams();
    if (params?.companyName) query.set('companyName', params.companyName);
    if (params?.startDate) query.set('startDate', params.startDate);
    if (params?.endDate) query.set('endDate', params.endDate);
    const qs = query.toString();
    return request.get<ReviewListItemDTO[]>(`/api/review/list${qs ? '?' + qs : ''}`);
  },

  /**
   * 获取复盘详情
   */
  getDetail: (reviewId: number): Promise<ReviewDetailDTO> => {
    return request.get<ReviewDetailDTO>(`/api/review/${reviewId}`);
  },

  /**
   * 轻量级状态查询（仅 artifact 状态，用于轮询）
   */
  getArtifactStatuses: (reviewId: number): Promise<ArtifactDTO[]> => {
    return request.get<ArtifactDTO[]>(`/api/review/${reviewId}/status`);
  },

  /**
   * 终止分析任务
   */
  cancelAnalysis: (reviewId: number): Promise<void> => {
    return request.post(`/api/review/${reviewId}/cancel`);
  },

  /**
   * 搜索复盘记录
   */
  search: (query: string): Promise<ReviewListItemDTO[]> => {
    return request.get<ReviewListItemDTO[]>(`/api/review/search?query=${encodeURIComponent(query)}`);
  },

  /**
   * 删除复盘记录
   */
  delete: (reviewId: number): Promise<void> => {
    return request.delete(`/api/review/${reviewId}`);
  },
};
