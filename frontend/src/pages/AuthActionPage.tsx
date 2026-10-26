import { FormEvent, useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { CheckCircle2, LockKeyhole, Sparkles } from 'lucide-react';
import { authApi } from '../api/auth';
import { getErrorMessage } from '../api/request';

interface AuthActionPageProps {
  mode: 'verify' | 'reset';
}

export default function AuthActionPage({ mode }: AuthActionPageProps) {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') ?? '';
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [status, setStatus] = useState<'loading' | 'ready' | 'success' | 'error'>(mode === 'verify' ? 'loading' : 'ready');
  const [message, setMessage] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (mode !== 'verify') return;
    if (!token) {
      setStatus('error');
      setMessage('验证链接无效或已过期。');
      return;
    }
    authApi.verifyEmail(token)
      .then(() => {
        setStatus('success');
        setMessage('邮箱验证成功，现在可以登录了。');
      })
      .catch(error => {
        setStatus('error');
        setMessage(getErrorMessage(error));
      });
  }, [mode, token]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!token) {
      setStatus('error');
      setMessage('重置链接无效或已过期。');
      return;
    }
    if (password !== confirmPassword) {
      setStatus('error');
      setMessage('两次输入的密码不一致。');
      return;
    }
    setSubmitting(true);
    setStatus('ready');
    setMessage('');
    try {
      await authApi.resetPassword(token, password);
      setStatus('success');
      setMessage('密码已重置，请使用新密码登录。');
    } catch (error) {
      setStatus('error');
      setMessage(getErrorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  const isVerify = mode === 'verify';

  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 via-indigo-50 to-primary-100 dark:from-slate-950 dark:via-slate-900 dark:to-indigo-950 flex items-center justify-center px-4 py-10">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="w-14 h-14 mx-auto mb-4 rounded-2xl bg-gradient-to-br from-primary-500 to-primary-600 text-white flex items-center justify-center shadow-xl shadow-primary-500/30">
            <Sparkles className="w-7 h-7" />
          </div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">AI Interview</h1>
          <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">账号安全设置</p>
        </div>

        <div className="bg-white dark:bg-slate-900 rounded-3xl border border-slate-200 dark:border-slate-700 shadow-xl p-7">
          {isVerify ? (
            <div className="text-center">
              {status === 'loading' && <p className="text-sm text-slate-500 dark:text-slate-400">正在验证邮箱...</p>}
              {status === 'success' && <CheckCircle2 className="mx-auto mb-4 h-12 w-12 text-emerald-500" />}
              <h2 className="text-lg font-semibold text-slate-900 dark:text-white">邮箱验证</h2>
              {message && <p className={`mt-3 text-sm ${status === 'error' ? 'text-red-600 dark:text-red-300' : 'text-slate-500 dark:text-slate-400'}`}>{message}</p>}
            </div>
          ) : (
            <>
              <h2 className="text-lg font-semibold text-slate-900 dark:text-white">重置密码</h2>
              <p className="mt-1 mb-6 text-sm text-slate-500 dark:text-slate-400">设置一个新的登录密码。</p>
              {status === 'success' ? (
                <div className="text-center">
                  <CheckCircle2 className="mx-auto mb-4 h-12 w-12 text-emerald-500" />
                  <p className="text-sm text-emerald-700 dark:text-emerald-300">{message}</p>
                </div>
              ) : (
                <form onSubmit={submit} className="space-y-4">
                  <label className="block">
                    <span className="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300">新密码</span>
                    <span className="relative block">
                      <LockKeyhole className="absolute left-3 top-3 h-4 w-4 text-slate-400" />
                      <input
                        required
                        type="password"
                        minLength={8}
                        maxLength={128}
                        value={password}
                        onChange={event => setPassword(event.target.value)}
                        autoComplete="new-password"
                        placeholder="至少 8 位密码"
                        className="w-full rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 pl-10 pr-3 py-2.5 text-sm text-slate-900 dark:text-white outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20"
                      />
                    </span>
                  </label>
                  <label className="block">
                    <span className="mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300">确认新密码</span>
                    <input
                      required
                      type="password"
                      minLength={8}
                      maxLength={128}
                      value={confirmPassword}
                      onChange={event => setConfirmPassword(event.target.value)}
                      autoComplete="new-password"
                      placeholder="再次输入新密码"
                      className="w-full rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2.5 text-sm text-slate-900 dark:text-white outline-none focus:border-primary-500 focus:ring-2 focus:ring-primary-500/20"
                    />
                  </label>
                  {message && <p role="alert" className="rounded-xl bg-red-50 dark:bg-red-950/30 px-3 py-2.5 text-sm text-red-600 dark:text-red-300">{message}</p>}
                  <button
                    type="submit"
                    disabled={submitting}
                    className="w-full rounded-xl bg-gradient-to-r from-primary-500 to-primary-600 py-3 text-sm font-semibold text-white shadow-lg shadow-primary-500/20 transition-all hover:from-primary-600 hover:to-primary-700 disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    {submitting ? '处理中...' : '保存新密码'}
                  </button>
                </form>
              )}
            </>
          )}

          {(status === 'success' || status === 'error') && (
            <button
              type="button"
              onClick={() => navigate('/login', { replace: true })}
              className="mt-6 w-full rounded-xl border border-slate-200 dark:border-slate-700 py-2.5 text-sm font-medium text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800"
            >
              返回登录
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
