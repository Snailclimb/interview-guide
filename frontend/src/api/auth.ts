import request from './request.ts';
import type { AuthState, AuthUser } from '../types/auth.ts';

export interface LoginPayload {
  email: string;
  password: string;
}

export interface RegisterPayload extends LoginPayload {
  nickname?: string;
}

export const authApi = {
  me(): Promise<AuthState> {
    return request.get<AuthState>('/api/auth/me');
  },

  login(payload: LoginPayload): Promise<AuthUser> {
    return request.post<AuthUser>('/api/auth/login', payload);
  },

  register(payload: RegisterPayload): Promise<AuthUser> {
    return request.post<AuthUser>('/api/auth/register', payload);
  },

  logout(): Promise<void> {
    return request.post<void>('/api/auth/logout');
  },

  verifyEmail(token: string): Promise<void> {
    return request.post<void>('/api/auth/verify-email', { token });
  },

  resendVerification(email: string): Promise<void> {
    return request.post<void>('/api/auth/resend-verification', { email });
  },

  requestPasswordReset(email: string): Promise<void> {
    return request.post<void>('/api/auth/password-reset/request', { email });
  },

  resetPassword(token: string, newPassword: string): Promise<void> {
    return request.post<void>('/api/auth/password-reset/confirm', { token, newPassword });
  },
};
