import { BarChart3, Brain, ClipboardCheck, MessageSquare } from 'lucide-react';
import { Link, useLocation } from 'react-router-dom';
import { ROUTES } from '../constants/routes';

const items = [
  { path: ROUTES.agent, label: '对话工作台', icon: MessageSquare },
  { path: ROUTES.agentMemory, label: '记忆管理', icon: Brain },
  { path: ROUTES.agentActions, label: '动作与训练', icon: ClipboardCheck },
  { path: ROUTES.agentUsage, label: '用量与活动', icon: BarChart3 },
] as const;

export default function AgentNavigation() {
  const location = useLocation();

  return (
    <nav aria-label="Agent 页面导航" className="flex flex-wrap gap-2 border-b border-slate-200 pb-3 dark:border-slate-700">
      {items.map(({ path, label, icon: Icon }) => {
        const active = location.pathname === path;
        return (
          <Link
            key={path}
            to={{ pathname: path, search: location.search }}
            aria-current={active ? 'page' : undefined}
            className={`inline-flex items-center gap-2 rounded-xl px-3.5 py-2 text-sm font-medium transition-colors ${active
              ? 'bg-primary-50 text-primary-700 shadow-sm dark:bg-primary-900/30 dark:text-primary-300'
              : 'text-slate-500 hover:bg-slate-100 hover:text-slate-700 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white'
              }`}
          >
            <Icon className="h-4 w-4" />
            {label}
          </Link>
        );
      })}
    </nav>
  );
}
