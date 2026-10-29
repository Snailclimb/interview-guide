export const ROUTES = {
  interview: '/interview',
  interviewCreate: (requestId: string) => `/interview/create/${requestId}`,
  interviewSession: (sessionId: string) => `/interview/session/${sessionId}`,
  resumeUpload: '/upload',
  resumeHistory: '/history',
  knowledgebaseUpload: '/knowledgebase/upload',
  agent: '/agent',
  agentMemory: '/agent/memory',
  agentActions: '/agent/actions',
  agentUsage: '/agent/usage',
} as const;

export const ROUTE_PATTERNS = {
  interviewCreate: 'interview/create/:requestId',
  interviewSession: 'interview/session/:activeSessionId',
} as const;
