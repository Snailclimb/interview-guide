import { BarChart3, Brain, ClipboardCheck, ArrowRight } from 'lucide-react';
import { Link, useLocation } from 'react-router-dom';
import { ROUTES } from '../constants/routes';

const items = [
  {
    path: ROUTES.agentMemory,
    title: '记忆管理',
    description: '查看跨面试保留的训练重点，支持编辑和删除。',
    icon: Brain,
  },
  {
    path: ROUTES.agentActions,
    title: '动作与训练',
    description: '确认 Agent 草稿，推进专项训练任务。',
    icon: ClipboardCheck,
  },
  {
    path: ROUTES.agentUsage,
    title: '用量与活动',
    description: '查看 Token、模型调用、耗时、费用和活动记录。',
    icon: BarChart3,
  },
] as const;

export default function AgentManagementOverview() {
  const location = useLocation();

  return (
    <section aria-label="Agent 管理入口" className="grid grid-cols-1 gap-3 md:grid-cols-3">
      {items.map(({ path, title, description, icon: Icon }) => (
        <Link
          key={path}
          to={{ pathname: path, search: location.search }}
          className="group rounded-2xl border border-slate-200 bg-white/80 p-4 transition-colors hover:border-primary-300 hover:bg-primary-50/40 dark:border-slate-700 dark:bg-slate-800/60 dark:hover:border-primary-700 dark:hover:bg-primary-900/15"
        >
          <div className="flex items-start justify-between gap-3">
            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-300">
              <Icon className="h-4 w-4" />
            </div>
            <ArrowRight className="h-4 w-4 text-slate-400 transition-transform group-hover:translate-x-0.5 group-hover:text-primary-500" />
          </div>
          <h2 className="mt-3 text-sm font-semibold dark-text">{title}</h2>
          <p className="mt-1 text-xs leading-5 dark-text-muted">{description}</p>
        </Link>
      ))}
    </section>
  );
}
