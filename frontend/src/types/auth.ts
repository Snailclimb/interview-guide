export interface AuthUser {
  id: string;
  email: string;
  nickname: string;
  status: string;
  emailVerified: boolean;
  lastLoginAt: string | null;
}

export interface AuthState {
  required: boolean;
  authenticated: boolean;
  user: AuthUser | null;
}
