// Enums matching backend
export type ArtifactType = 'QUESTION_ANALYSIS' | 'PROJECT_ANALYSIS' | 'QUESTION_RECORD';
export type ReviewStatus = 'DRAFT' | 'ANALYZED';
export type AsyncTaskStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

// Artifact type display names
export const ARTIFACT_TYPE_LABELS: Record<ArtifactType, string> = {
  QUESTION_ANALYSIS: '问题分类分析',
  PROJECT_ANALYSIS: '项目经历沉淀',
  QUESTION_RECORD: '反问记录',
};

// DTOs matching backend records
export interface ArtifactDTO {
  id: number;
  type: ArtifactType;
  content: string;
  version: number;
  updatedAt: string;
  status: AsyncTaskStatus | null;
  error: string | null;
}

export interface ReviewListItemDTO {
  id: number;
  companyName: string;
  position: string;
  roundNumber: number;
  interviewDate: string;
  status: ReviewStatus;
  createdAt: string;
  questionAnalysisDone: boolean;
  projectAnalysisDone: boolean;
  questionRecordDone: boolean;
}

export interface ReviewDetailDTO {
  id: number;
  scheduleId: number;
  transcriptStorageUrl: string;
  transcriptText: string;
  companyName: string;
  position: string;
  roundNumber: number;
  interviewDate: string;
  status: ReviewStatus;
  createdAt: string;
  updatedAt: string;
  artifacts: ArtifactDTO[];
}

export interface AnalyzeRequest {
  type: ArtifactType;
}

export interface UpdateArtifactRequest {
  content: string;
}
