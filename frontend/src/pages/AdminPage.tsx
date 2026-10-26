import { useEffect, useState } from 'react';
import { ShieldCheck, UserCheck, UserRoundX, RefreshCw } from 'lucide-react';
import { adminApi } from '../api/admin';
import type { AdminAgentAuditEvent, AdminUser, SecurityAuditEvent } from '../types/auth';
import { getErrorMessage } from '../api/request';

export default function AdminPage() {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [events, setEvents] = useState<SecurityAuditEvent[]>([]);
  const [agentEvents, setAgentEvents] = useState<AdminAgentAuditEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState('');

  async function load() {
    setLoading(true);
    setNotice('');
    try {
      const [nextUsers, nextEvents, nextAgentEvents] = await Promise.all([
        adminApi.users(), adminApi.auditEvents(), adminApi.agentAuditEvents(),
      ]);
      setUsers(nextUsers);
      setEvents(nextEvents);
      setAgentEvents(nextAgentEvents);
    } catch (error) {
      setNotice(getErrorMessage(error));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  async function changeStatus(user: AdminUser) {
    try {
      const updated = await adminApi.updateStatus(user.id, user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE');
      setUsers(current => current.map(item => item.id === updated.id ? updated : item));
    } catch (error) {
      setNotice(getErrorMessage(error));
    }
  }

  async function changeRole(user: AdminUser) {
    try {
      const updated = await adminApi.updateRole(user.id, user.role === 'ADMIN' ? 'USER' : 'ADMIN');
      setUsers(current => current.map(item => item.id === updated.id ? updated : item));
    } catch (error) {
      setNotice(getErrorMessage(error));
    }
  }

  return (
    <div className="max-w-6xl mx-auto space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <div className="flex items-center gap-3">
            <ShieldCheck className="w-7 h-7 text-primary-500" />
            <h1 className="text-2xl font-bold text-slate-900 dark:text-white">管理员治理</h1>
          </div>
          <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">管理账号状态和角色，操作只记录低敏安全审计。</p>
        </div>
        <button type="button" onClick={() => void load()} className="btn-secondary inline-flex items-center gap-2 px-4 py-2 rounded-xl text-sm" disabled={loading}>
          <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} /> 刷新
        </button>
      </div>

      {notice && <p role="alert" className="rounded-xl bg-red-50 dark:bg-red-950/30 px-4 py-3 text-sm text-red-600 dark:text-red-300">{notice}</p>}

      <section className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 overflow-hidden">
        <div className="px-5 py-4 border-b border-slate-100 dark:border-slate-800">
          <h2 className="font-semibold text-slate-900 dark:text-white">账号列表</h2>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 dark:bg-slate-800/70 text-slate-500 dark:text-slate-400">
              <tr><th className="px-5 py-3 text-left">账号</th><th className="px-5 py-3 text-left">角色</th><th className="px-5 py-3 text-left">状态</th><th className="px-5 py-3 text-right">操作</th></tr>
            </thead>
            <tbody>
              {users.map(user => (
                <tr key={user.id} className="border-t border-slate-100 dark:border-slate-800">
                  <td className="px-5 py-4"><div className="font-medium text-slate-800 dark:text-slate-200">{user.nickname || '未设置昵称'}</div><div className="text-xs text-slate-400">{user.email}</div></td>
                  <td className="px-5 py-4"><span className="rounded-full px-2.5 py-1 text-xs bg-indigo-50 text-indigo-600 dark:bg-indigo-950/40 dark:text-indigo-300">{user.role}</span></td>
                  <td className="px-5 py-4"><span className={`rounded-full px-2.5 py-1 text-xs ${user.status === 'ACTIVE' ? 'bg-emerald-50 text-emerald-600 dark:bg-emerald-950/40 dark:text-emerald-300' : 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400'}`}>{user.status}</span></td>
                  <td className="px-5 py-4"><div className="flex justify-end gap-2"><button type="button" onClick={() => void changeRole(user)} className="btn-secondary inline-flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs"><UserCheck className="w-3.5 h-3.5" />{user.role === 'ADMIN' ? '降为用户' : '设为管理员'}</button><button type="button" onClick={() => void changeStatus(user)} className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800"><UserRoundX className="w-3.5 h-3.5" />{user.status === 'ACTIVE' ? '停用' : '启用'}</button></div></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 overflow-hidden">
        <div className="px-5 py-4 border-b border-slate-100 dark:border-slate-800"><h2 className="font-semibold text-slate-900 dark:text-white">安全审计</h2></div>
        <div className="divide-y divide-slate-100 dark:divide-slate-800">{events.length === 0 ? <p className="px-5 py-6 text-sm text-slate-400">暂无管理员操作记录</p> : events.slice(0, 20).map(event => <div key={event.id} className="px-5 py-3 flex items-center justify-between gap-4 text-sm"><span className="text-slate-700 dark:text-slate-300">{event.action}</span><span className="text-xs text-slate-400">{event.result} · {new Date(event.createdAt).toLocaleString()}</span></div>)}</div>
      </section>

      <section className="rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 overflow-hidden">
        <div className="px-5 py-4 border-b border-slate-100 dark:border-slate-800">
          <h2 className="font-semibold text-slate-900 dark:text-white">Agent 运行审计</h2>
          <p className="mt-1 text-xs text-slate-400">仅展示账号标识、运行动作和工具名称，不包含 Prompt、回答或工具参数。</p>
        </div>
        <div className="divide-y divide-slate-100 dark:divide-slate-800">
          {agentEvents.length === 0 ? <p className="px-5 py-6 text-sm text-slate-400">暂无 Agent 操作记录</p> : agentEvents.slice(0, 30).map(event => (
            <div key={event.id} className="px-5 py-3 flex items-center justify-between gap-4 text-sm">
              <div className="min-w-0"><span className="text-slate-700 dark:text-slate-300">{event.action}</span><span className="ml-2 text-xs text-slate-400">{event.resourceId || event.resourceType}</span></div>
              <div className="shrink-0 text-right text-xs text-slate-400"><div>{event.result || 'unknown'} · {event.ownerKey || 'unknown'}</div><div>{new Date(event.createdAt).toLocaleString()}</div></div>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
