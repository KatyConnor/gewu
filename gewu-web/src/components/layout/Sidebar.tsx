'use client';
import { memo, useState, useEffect } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { RootState } from '@/store';
import { setPage } from '@/store';
import { getUser } from '@/lib/token';
import { PageType } from '@/types';
import { listCurrentMenus, type MenuDTO } from '@/lib/menu';
import { Wallet, Home, MessageSquare, FolderOpen, Bot, Settings, BarChart3, PenTool, FileText, GitBranch, BookOpen, Zap, Shield, Search, ArrowLeft, Lock, Server, Users, KeyRound, ClipboardCheck, Menu, Building2, Network } from 'lucide-react';

interface NavItem { id: PageType; label: string; icon: React.ComponentType<{ className?: string }>; badge?: string; colorClass?: string; noAction?: boolean }

/** 图标名称 -> lucide 组件映射（后端菜单种子的 icon 字段对应） */
const iconMap: Record<string, React.ComponentType<{ className?: string }>> = {
  Home, MessageSquare, FolderOpen, FileText, PenTool, GitBranch,
  Bot, Shield, BookOpen, Zap, Lock, Server,
  BarChart3, Settings, Users, KeyRound, ClipboardCheck, Menu, Building2,
  Network, Wallet,
};

/** 静态菜单（API 失败时的 fallback，避免导航丢失） */
const navGroups: { label: string; items: NavItem[] }[] = [
  { label: '工作空间', items: [
    { id: 'dashboard', label: '主页', icon: Home },
    { id: 'chat', label: '会话', icon: MessageSquare, badge: '3' },
    { id: 'projects', label: '项目管理', icon: FolderOpen },
    { id: 'workspace' as PageType, label: '我的文件', icon: FolderOpen },
    { id: 'dev-workspace' as PageType, label: '开发空间', icon: GitBranch },
    { id: 'requirements', label: '需求管理', icon: FileText, badge: '12' },
    { id: 'prototype', label: '原型', icon: PenTool },
    { id: 'workflow', label: '工作流', icon: GitBranch },
    { id: 'orchestration' as PageType, label: '编排引擎', icon: Network },
  ]},
  { label: '智能体', items: [
    { id: 'agent-market', label: '智能体广场', icon: Bot, colorClass: 'text-cyber-400/70' },
    { id: 'my-agents', label: '我的智能体', icon: Shield, colorClass: 'text-cyber-400/70' },
    { id: 'agent-manage', label: '智能体管理', icon: Bot, colorClass: 'text-cyber-400/70' },
    { id: 'skill-library', label: '技能库', icon: BookOpen, colorClass: 'text-cyber-400/70' },
    { id: 'my-skills', label: '我的技能', icon: Zap, colorClass: 'text-cyber-400/70' },
    { id: 'sandbox', label: '沙箱安全', icon: Lock, colorClass: 'text-cyber-400/70' },
    { id: 'mcp-server', label: 'MCP Server', icon: Server, colorClass: 'text-cyber-400/70' },
  ]},
  { label: '数据', items: [
    { id: 'usage' as PageType, label: '用量统计', icon: BarChart3 },
    { id: 'settings', label: '设置', icon: Settings },
  ]},
  { label: '系统管理', items: [
    { id: 'audit-center' as PageType, label: '审批中心', icon: ClipboardCheck, colorClass: 'text-cyber-400/70' },
  ]},
];

/** 将后端菜单树转换为前端 navGroups 格式 */
function menuTreeToGroups(menus: MenuDTO[]): { label: string; items: NavItem[] }[] {
  return menus
    .filter(m => m.menuType === 1)
    .map(dir => ({
      label: dir.menuName,
      items: (dir.children || [])
        .filter(m => m.menuType === 2)
        .map(m => ({
          id: (m.path || 'dashboard') as PageType,
          label: m.menuName,
          icon: (m.icon && iconMap[m.icon]) || Home,
          colorClass: dir.menuName === '智能体' || dir.menuName === '系统管理' ? 'text-cyber-400/70' : undefined,
        })),
    }))
    .filter(g => g.items.length > 0);
}

function SidebarInner() {
  const dispatch = useDispatch();
  const currentPage = useSelector((s: RootState) => s.app.currentPage);
  const user = useSelector((s: RootState) => s.app.user);
  const isHome = currentPage === 'dashboard';
  const [dynamicGroups, setDynamicGroups] = useState<{ label: string; items: NavItem[] }[] | null>(null);

  // 挂载时拉取当前用户菜单树；失败则 fallback 到静态 navGroups
  useEffect(() => {
    let cancelled = false;
    listCurrentMenus()
      .then(menus => {
        if (cancelled) return;
        const groups = menuTreeToGroups(menus);
        if (groups.length > 0) setDynamicGroups(groups);
      })
      .catch(() => {
        // API 失败保留静态 navGroups，避免导航丢失
      });
    return () => { cancelled = true; };
  }, []);

  // 动态菜单已由后端按权限过滤；静态 fallback 需客户端过滤系统管理组
  const groups = dynamicGroups || navGroups;
  const visibleGroups = dynamicGroups
    ? groups
    : groups.filter(g => g.label !== '系统管理' || (getUser()?.roles || []).includes('ADMIN'));

  return (
    <aside className="w-64 border-r flex flex-col fixed h-full z-30" style={{ background: 'rgba(8,18,17,0.92)', borderColor: 'rgba(0,184,148,0.08)' }}>
      {/* Logo */}
      <div className="p-5 flex items-center gap-3">
        <div className="w-9 h-9 rounded-md flex items-center justify-center flex-shrink-0 relative overflow-hidden">
          <img src="/logo/logo1.png" alt="格物致虚" className="w-full h-full object-cover rounded-md" />
        </div>
        <div>
          <h1 className="calligraphy-text text-base font-bold text-ink-50">格物<span className="text-tech-400/60 mx-0.5">·</span>致虚</h1>
          <p className="text-[10px] text-ink-500">AI 协作平台</p>
        </div>
      </div>
      {/* 搜索 */}
      <div className="px-4 mb-2">
        <div className="flex items-center gap-2 px-3 py-2 bg-ink-800/50 rounded-lg border border-tech-500/10 focus-within:border-tech-500/25 transition-colors">
          <Search className="w-4 h-4 text-ink-500" />
          <input type="text" placeholder="搜索..." className="bg-transparent text-sm text-ink-200 placeholder-ink-500 outline-none flex-1" />
          <span className="text-xs text-tech-400/60 border border-tech-500/15 px-1.5 rounded bg-tech-500/5">⌘K</span>
        </div>
      </div>
      {/* 导航 */}
      <nav className="flex-1 px-3 py-2 space-y-0.5 overflow-y-auto scrollbar-thin">
        {visibleGroups.map(group => (
          <div key={group.label}>
            <p className="px-3 py-2 text-[10px] font-medium text-ink-500 uppercase tracking-wider">{group.label}</p>
            {group.items.map(item => (
              <button key={item.id} onClick={() => !item.noAction && dispatch(setPage(item.id))}
                className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition-all ${
                  currentPage === item.id
                    ? 'nav-item active text-ink-100'
                    : 'text-ink-300 hover:bg-nav-hover'
                }`}>
                <item.icon className={`w-4 h-4 ${currentPage === item.id ? 'text-tech-400' : (item.colorClass || 'text-ink-400')}`} />
                <span className={currentPage === item.id ? 'text-ink-100' : 'text-ink-300'}>{item.label}</span>
                {item.badge && <span className="ml-auto text-xs bg-tech-500/15 text-tech-400 px-1.5 py-0.5 rounded-full">{item.badge}</span>}
              </button>
            ))}
          </div>
        ))}
      </nav>
      {/* 底部用户名片 */}
      <div className="p-3 border-t" style={{ borderColor: 'rgba(0,184,148,0.08)' }}>
        <div className="flex items-center gap-3 px-2 py-1.5">
          <div className="relative">
            <div className="w-8 h-8 rounded-full bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center text-white font-semibold text-xs">{user.avatar}</div>
            <div className="absolute -bottom-0.5 -right-0.5 w-2.5 h-2.5 bg-tech-500 rounded-full status-online border-2 border-ink-950"></div>
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-xs text-ink-100 truncate font-medium">{user.name}</p>
            <p className="text-[10px] text-ink-500 truncate">{user.department} · {user.role}</p>
          </div>
          {isHome ? (
            <button onClick={() => dispatch(setPage('settings'))} className="p-1.5 text-ink-500 hover:text-tech-400 hover:bg-nav-hover rounded-md transition-all" title="设置">
              <Settings className="w-4 h-4" />
            </button>
          ) : (
            <button onClick={() => dispatch(setPage('dashboard'))} className="p-1.5 text-ink-500 hover:text-tech-400 hover:bg-nav-hover rounded-md transition-all" title="返回">
              <ArrowLeft className="w-4 h-4" />
            </button>
          )}
        </div>
      </div>
    </aside>
  );
}

export default memo(SidebarInner);
