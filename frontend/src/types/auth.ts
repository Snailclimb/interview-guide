export interface AuthUser {
  id: string;
  email: string;
  nickname: string;
  status: string;
  role: string;
  emailVerified: boolean;
  lastLoginAt: string | null;
}

export interface AuthState {
  required: boolean;
  authenticated: boolean;
  user: AuthUser | null;
}

export interface AdminUser {
  id: string;
  email: string;
  nickname: string;
  status: string;
  role: string;
  emailVerified: boolean;
  lastLoginAt: string | null;
  createdAt: string | null;
}

export interface SecurityAuditEvent {
  id: number;
  actorUserId: string | null;
  targetUserId: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  result: string | null;
  createdAt: string;
}

export interface AdminAgentAuditEvent {
  id: number;
  ownerKey: string | null;
  interviewSessionId: string | null;
  agentSessionId: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  result: string | null;
  createdAt: string;
}
