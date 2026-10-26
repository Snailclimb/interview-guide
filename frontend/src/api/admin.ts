import request from './request.ts';
import type { AdminAgentAuditEvent, AdminUser, SecurityAuditEvent } from '../types/auth.ts';

export const adminApi = {
  users(limit = 100): Promise<AdminUser[]> {
    return request.get<AdminUser[]>(`/api/admin/users?limit=${Math.max(1, Math.min(200, limit))}`);
  },

  auditEvents(limit = 100): Promise<SecurityAuditEvent[]> {
    return request.get<SecurityAuditEvent[]>(`/api/admin/audit/events?limit=${Math.max(1, Math.min(200, limit))}`);
  },

  agentAuditEvents(limit = 100): Promise<AdminAgentAuditEvent[]> {
    return request.get<AdminAgentAuditEvent[]>(
      `/api/admin/agent-audit/events?limit=${Math.max(1, Math.min(200, limit))}`,
    );
  },

  updateStatus(userId: string, status: 'ACTIVE' | 'DISABLED'): Promise<AdminUser> {
    return request.patch<AdminUser>(`/api/admin/users/${encodeURIComponent(userId)}/status`, { status });
  },

  updateRole(userId: string, role: 'USER' | 'ADMIN'): Promise<AdminUser> {
    return request.patch<AdminUser>(`/api/admin/users/${encodeURIComponent(userId)}/role`, { role });
  },
};
