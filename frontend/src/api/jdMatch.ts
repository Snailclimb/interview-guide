import { request } from './request';
import type {
  JdMatchCreateRequest,
  JdMatchCreateResponse,
  JdMatchDetail,
  JdMatchListItem,
} from '../types/jdMatch';

export const jdMatchApi = {
  /**
   * 创建 JD 匹配分析任务（异步）
   */
  async createMatch(data: JdMatchCreateRequest): Promise<JdMatchCreateResponse> {
    return request.post<JdMatchCreateResponse>('/api/jd-matches', data);
  },

  /**
   * 获取所有匹配分析记录
   */
  async getAllMatches(): Promise<JdMatchListItem[]> {
    return request.get<JdMatchListItem[]>('/api/jd-matches');
  },

  /**
   * 获取匹配分析详情（可轮询状态）
   */
  async getMatchDetail(id: number): Promise<JdMatchDetail> {
    return request.get<JdMatchDetail>(`/api/jd-matches/${id}`);
  },

  /**
   * 删除匹配分析记录
   */
  async deleteMatch(id: number): Promise<void> {
    return request.delete<void>(`/api/jd-matches/${id}`);
  },
};
