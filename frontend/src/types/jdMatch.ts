// JD 匹配分析类型定义

export interface JdMatchCreateRequest {
  resumeId: number;
  jdTitle?: string;
  jdText: string;
}

export interface JdMatchCreateResponse {
  id: number;
  status: string;
}

export type JdMatchStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface JdMatchListItem {
  id: number;
  resumeId: number;
  resumeFilename: string;
  jdTitle: string | null;
  overallScore: number | null;
  status: JdMatchStatus;
  error: string | null;
  createdAt: string;
}

export interface JdMatchScoreDetail {
  hardRequirementScore: number;  // 硬性要求匹配度 (0-25)
  skillMatchScore: number;       // 技能栈匹配度 (0-25)
  experienceScore: number;       // 经验与业务匹配度 (0-20)
  projectScore: number;          // 项目经历匹配度 (0-20)
  softSkillScore: number;        // 软素质匹配度 (0-10)
}

export interface JdRequirement {
  requirement: string;
  category: string;
  priority: 'CORE' | 'NORMAL';
  matched: boolean;
  evidence: string;
}

export interface JdGap {
  gap: string;
  impact: '高' | '中' | '低';
  suggestion: string;
}

export interface JdSuggestion {
  category: string;
  priority: '高' | '中' | '低';
  issue: string;
  recommendation: string;
}

export interface JdMatchDetail {
  id: number;
  resumeId: number;
  resumeFilename: string;
  jdTitle: string | null;
  jdText: string;
  overallScore: number | null;
  scoreDetail: JdMatchScoreDetail | null;
  summary: string | null;
  jdRequirements: JdRequirement[];
  strengths: string[];
  gaps: JdGap[];
  suggestions: JdSuggestion[];
  interviewQuestions: string[];
  status: JdMatchStatus;
  error: string | null;
  createdAt: string;
}
