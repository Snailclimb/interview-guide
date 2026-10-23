import { request } from './request';

export type AnalyzeStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
export type EvaluateStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface ResumeListItem {
  id: number;
  filename: string;
  fileSize: number;
  uploadedAt: string;
  accessCount: number;
  latestScore?: number;
  lastAnalyzedAt?: string;
  interviewCount: number;
  analyzeStatus?: AnalyzeStatus;
  analyzeError?: string;
  storageUrl?: string;
}

export interface ResumeStats {
  totalCount: number;
  totalInterviewCount: number;
  totalAccessCount: number;
}

export interface AnalysisItem {
  id: number;
  overallScore: number;
  contentScore: number;
  structureScore: number;
  skillMatchScore: number;
  expressionScore: number;
  projectScore: number;
  summary: string;
  analyzedAt: string;
  strengths: string[];
  suggestions: unknown[];
}

export interface InterviewItem {
  id: number;
  sessionId: string;
  title?: string | null;
  totalQuestions: number;
  status: string;
  sourceType?: string;
  knowledgeBaseId?: number | null;
  skillId?: string | null;
  evaluateStatus?: EvaluateStatus;
  evaluateError?: string;
  overallScore: number | null;
  overallFeedback: string | null;
  createdAt: string;
  completedAt: string | null;
  questions?: unknown[];
  strengths?: string[];
  improvements?: string[];
  referenceAnswers?: unknown[];
}

export interface AnswerItem {
  questionIndex: number;
  question: string;
  category: string;
  userAnswer: string;
  score: number;
  feedback: string;
  referenceAnswer?: string;
  keyPoints?: string[];
  answeredAt: string;
}

export interface ResumeDetail {
  id: number;
  filename: string;
  fileSize: number;
  contentType: string;
  storageUrl: string;
  uploadedAt: string;
  accessCount: number;
  resumeText: string;
  analyzeStatus?: AnalyzeStatus;
  analyzeError?: string;
  analyses: AnalysisItem[];
  interviews: InterviewItem[];
}

export interface ResumeEditorContent {
  name: string;
  email: string;
  phone: string;
  location: string;
  targetPosition: string;
  skills: string;
  workExperience: string;
  projects: string;
  education: string;
  selfEvaluation: string;
  rawText: string;
}

export type ResumeEditorSource = 'ORIGINAL' | 'AI_DRAFT' | 'MANUAL' | 'APPLIED';

export interface ResumeEditor {
  resumeId: number;
  version: number;
  source: ResumeEditorSource;
  updatedAt: string;
  content: ResumeEditorContent;
  originalText: string;
  appliedToInterview: boolean;
  analysisOutdated: boolean;
}

export interface InterviewDetail extends InterviewItem {
  evaluateStatus?: EvaluateStatus;
  evaluateError?: string;
  answers: AnswerItem[];
}

export const historyApi = {
  /**
   * 获取所有简历列表
   */
  async getResumes(): Promise<ResumeListItem[]> {
    return request.get<ResumeListItem[]>('/api/resumes');
  },

  /**
   * 获取简历详情
   */
  async getResumeDetail(id: number, signal?: AbortSignal): Promise<ResumeDetail> {
    return request.get<ResumeDetail>(`/api/resumes/${id}/detail`, { signal });
  },

  /**
   * 获取面试详情
   */
  async getInterviewDetail(sessionId: string): Promise<InterviewDetail> {
    return request.get<InterviewDetail>(`/api/interview/sessions/${sessionId}/details`);
  },

  /**
   * 导出简历分析报告PDF
   */
  async exportAnalysisPdf(resumeId: number): Promise<Blob> {
    return request.download(`/api/resumes/${resumeId}/export`);
  },

  /**
   * 导出面试报告PDF
   */
  async exportInterviewPdf(sessionId: string): Promise<Blob> {
    return request.download(`/api/interview/sessions/${sessionId}/export`);
  },

  /**
   * 删除简历
   */
  async deleteResume(id: number): Promise<void> {
    return request.delete(`/api/resumes/${id}`);
  },

  /**
   * 删除面试记录
   */
  async deleteInterview(sessionId: string): Promise<void> {
    return request.delete(`/api/interview/sessions/${sessionId}`);
  },

  /**
   * 修改面试记录名称
   */
  async renameInterview(sessionId: string, title: string): Promise<void> {
    return request.patch(`/api/interview/sessions/${encodeURIComponent(sessionId)}/title`, { title });
  },

  /**
   * 获取简历统计信息
   */
  async getStatistics(): Promise<ResumeStats> {
    return request.get<ResumeStats>('/api/resumes/statistics');
  },

  /**
   * 重新分析简历
   */
  async reanalyze(id: number): Promise<void> {
    return request.post(`/api/resumes/${id}/reanalyze`);
  },

  async getResumeEditor(id: number): Promise<ResumeEditor> {
    return request.get<ResumeEditor>(`/api/resumes/${id}/editor`);
  },

  async saveResumeEditor(id: number, content: ResumeEditorContent): Promise<ResumeEditor> {
    return request.put<ResumeEditor>(`/api/resumes/${id}/editor`, {content});
  },

  async optimizeResumeEditor(id: number, content: ResumeEditorContent): Promise<ResumeEditor> {
    return request.post<ResumeEditor>(`/api/resumes/${id}/editor/ai-optimize`, {content});
  },

  async applyResumeEditor(id: number): Promise<ResumeEditor> {
    return request.post<ResumeEditor>(`/api/resumes/${id}/editor/apply`);
  },

  async exportResumePdf(id: number): Promise<Blob> {
    return request.download(`/api/resumes/${id}/editor/export/pdf`);
  },

  async exportResumeDocx(id: number): Promise<Blob> {
    return request.download(`/api/resumes/${id}/editor/export/docx`);
  },
};
