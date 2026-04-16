// frontend/src/types/interviewSchedule.ts

export type InterviewStatus = 'PENDING' | 'COMPLETED' | 'CANCELLED' | 'RESCHEDULED';

export type InterviewType = 'ONSITE' | 'VIDEO' | 'PHONE';

export interface InterviewSchedule {
  id: number;
  companyName: string;
  position: string;
  interviewTime: string; // ISO 8601
  interviewType: InterviewType;
  meetingLink?: string;
  roundNumber: number;
  interviewer?: string;
  notes?: string;
  status: InterviewStatus;
  createdAt: string;
  updatedAt: string;
}

export interface CreateInterviewRequest {
  companyName: string;
  position: string;
  interviewTime: string;
  interviewType?: InterviewType;
  meetingLink?: string;
  roundNumber?: number;
  interviewer?: string;
  notes?: string;
}

export interface ParseRequest {
  rawText: string;
  source?: 'feishu' | 'tencent' | 'zoom' | 'other';
}

export interface ParseResponse {
  success: boolean;
  data: CreateInterviewRequest | null;
  confidence: number;
  parseMethod: 'rule' | 'ai';
  log: string;
}

export interface InterviewFormData extends CreateInterviewRequest {
  id?: number;
}
