import { FormEvent, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { LockKeyhole, Mail, Sparkles, UserRound } from 'lucide-react';
import { authApi } from '../api/auth';
import { getErrorMessage } from '../api/request';

type AuthMode = 'login' | 'register' | 'forgot';

function safeRedirect(value: string | null): string {
  return value && value.startsWith('/') && !value.startsWith('//') ? value : '/history';
}

export default function AuthPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [mode, setMode] = useState<AuthMode>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [nickname, setNickname] = useState('');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const isRegister = mode === 'register';
  const isForgot = mode === 'forgot';

  function changeMode(nextMode: AuthMode) {
    setMode(nextMode);
    setError('');
    setSuccess('');
    setPassword('');
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setSuccess('');
    setSubmitting(true);
    try {
      if (isForgot) {
        await authApi.requestPasswordReset(email);
        setSuccess('如果该邮箱已注册，我们会发送密码重置链接，请查收邮件。');
        return;
      }

      if (isRegister) {
        const user = await authApi.register({ email, password, nickname: nickname.trim() || undefined });
        if (!user.emailVerified) {
          setSuccess('账号已创建，请查收验证邮件。完成验证后再登录。');
          setMode('login');
          return;
        }
      } else {
        await authApi.login({ email, password });
      }
      navigate(safeRedirect(searchParams.get('redirect')), { replace: true });
    } catch (submitError) {
      setError(getErrorMessage(submitError));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-indigo-50 to-primary-100 dark:from-slate-950 dark:via-slate-900 dark:to-indigo-950 flex items-center justify-center px-4 py-10">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="w-14 h-14 mx-auto mb-4 rounded-2xl bg-gradient-to-br from-primary-500 to-primary-600 text-white flex items-center justify-center shadow-xl shadow-primary-500/30">
            <Sparkles className="w-7 h-7" />
          </div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">AI Interview</h1>
          <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">登录后同步你的简历、面试和 Agent 会话</p>
        </div>

        <div className="bg-white dark:bg-slate-900 rounded-3xl border border-slate-200 dark:border-slate-700 shadow-xl p-7">
          {!isForgot ? (
            <div className="flex gap-1 p-1 mb-6 rounded-xl bg-slate-100 dark:bg-slate-800">
              {(['login', 'register'] as AuthMode[]).map(item => (
                <button
                  key={item}
                  type="button"
                  onClick={() => changeMode(item)}
                  className={`flex-1 py-2 rounded-lg text-sm font-medium transition-colors ${mode === item
                    ? 'bg-white dark:bg-slate-700 text-primary-600 dark:text-primary-300 shadow-sm'
                    : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'
                  }`}
                >
                  {item === 'login' ? '登录' : '注册'}
                </button>
              ))}
            </div>
          ) : (
            <div className="mb-6">
              <h2 className="text-lg font-semibold text-slate-900 dark:text-white">找回密码</h2>
              <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">输入注册邮箱，我们会发送重置链接。</p>
            </div>
          )}

          <form onSubmit={submit} className="space-y-4">
            {isRegister && (
              <label className="block">
                <span className="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300">昵称</span>
                <span className="relative block">
                  <UserRound className="absolute left-3 top-3 w-4 h-4 text-slate-400" />
                  <input
                    value={nickname}
                    onChange={event => setNickname(event.target.value)}
                    maxLength={80}
                    placeholder="例如：小明"
                    className="w-full rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 pl-10 pr-3 py-2.5 text-sm text-slate-900 dark:text-white outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20"
                  />
                </span>
              </label>
            )}

            <label className="block">
              <span className="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300">邮箱</span>
              <span className="relative block">
                <Mail className="absolute left-3 top-3 w-4 h-4 text-slate-400" />
                <input
                  required
                  type="email"
                  value={email}
                  onChange={event => setEmail(event.target.value)}
                  autoComplete="email"
                  placeholder="you@example.com"
                  className="w-full rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 pl-10 pr-3 py-2.5 text-sm text-slate-900 dark:text-white outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20"
                />
              </span>
            </label>

            {!isForgot && (
              <label className="block">
                <span className="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300">密码</span>
                <span className="relative block">
                  <LockKeyhole className="absolute left-3 top-3 w-4 h-4 text-slate-400" />
                  <input
                    required
                    type="password"
                    minLength={isRegister ? 8 : undefined}
                    maxLength={128}
                    value={password}
                    onChange={event => setPassword(event.target.value)}
                    autoComplete={isRegister ? 'new-password' : 'current-password'}
                    placeholder={isRegister ? '至少 8 位密码' : '请输入密码'}
                    className="w-full rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 pl-10 pr-3 py-2.5 text-sm text-slate-900 dark:text-white outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20"
                  />
                </span>
              </label>
            )}

            {mode === 'login' && (
              <button
                type="button"
                onClick={() => changeMode('forgot')}
                className="text-sm text-primary-600 dark:text-primary-300 hover:underline"
              >
                忘记密码？
              </button>
            )}

            {error && <p role="alert" className="rounded-xl bg-red-50 dark:bg-red-950/30 px-3 py-2.5 text-sm text-red-600 dark:text-red-300">{error}</p>}
            {success && <p role="status" className="rounded-xl bg-emerald-50 dark:bg-emerald-950/30 px-3 py-2.5 text-sm text-emerald-700 dark:text-emerald-300">{success}</p>}

            <button
              type="submit"
              disabled={submitting}
              className="w-full rounded-xl bg-gradient-to-r from-primary-500 to-primary-600 py-3 text-sm font-semibold text-white shadow-lg shadow-primary-500/20 transition-all hover:from-primary-600 hover:to-primary-700 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {submitting ? '处理中...' : isForgot ? '发送重置链接' : isRegister ? '创建账号并登录' : '登录'}
            </button>

            {isForgot && (
              <button
                type="button"
                onClick={() => changeMode('login')}
                className="w-full text-sm text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200"
              >
                返回登录
              </button>
            )}
          </form>
        </div>
      </div>
    </div>
  );
}
