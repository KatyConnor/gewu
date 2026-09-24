'use client';
import { useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { useRouter } from 'next/navigation';
import { RootState, setPage, clearAuth } from '@/store';
import { PageType } from '@/types';
import { Users, KeyRound, Wallet, Shield, ListTree, Building2, ShieldCheck, LayoutDashboard, LogOut } from 'lucide-react';
import { getUser, clearTokens } from '@/lib/token';

/** 后台管理端侧栏（静态菜单，全部为系统管理功能） */
export default function Sidebar() {
  const dispatch = useDispatch();
  const router = useRouter();
  const currentPage = useSelector((s: RootState) => s.app.currentPage);
  const user = useSelector((s: RootState) => s.app.user);
  const [open, setOpen] = useState(true);

  const navItems: { id: PageType; label: string; icon: React.ComponentType<{ className?: string }> }[] = [
    { id: 'dashboard', label: '总览', icon: LayoutDashboard },
    { id: 'user-manage', label: '用户管理', icon: Users },
    { id: 'role-manage', label: '角色权限', icon: KeyRound },
    { id: 'menu-manage', label: '菜单管理', icon: ListTree },
    { id: 'org-manage', label: '机构管理', icon: Building2 },
    { id: 'quota-manage', label: '配额套餐', icon: Wallet },
    { id: 'skill-audit', label: '技能审核', icon: Shield },
    { id: 'sandbox-audit', label: '沙箱审计', icon: ShieldCheck },
  ];

  const handleLogout = () => {
    clearTokens();
    dispatch(clearAuth());
    router.refresh();
  };

  return (
    <aside className={`flex flex-col h-screen glass-dark border-r border-glass-border transition-all ${open ? 'w-60' : 'w-16'}`}>
      <div className="flex items-center gap-2.5 px-4 py-5 border-b border-glass-border">
        <div className="w-9 h-9 rounded-lg bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center flex-shrink-0">
          <ShieldCheck className="w-5 h-5 text-white" />
        </div>
        {open && <div><p className="text-sm font-semibold text-ink-50">格物管理端</p><p className="text-[10px] text-ink-500">Admin Console</p></div>}
      </div>
      <nav className="flex-1 px-3 py-4 space-y-1 overflow-y-auto scrollbar-thin">
        {navItems.map(item => (
          <button key={item.id} onClick={() => dispatch(setPage(item.id))}
            className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-lg text-sm transition-all ${currentPage === item.id ? 'bg-tech-500/15 text-tech-400' : 'text-ink-400 hover:text-ink-200 hover:bg-ink-800/40'}`}>
            <item.icon className="w-4 h-4 flex-shrink-0" />
            {open && <span>{item.label}</span>}
          </button>
        ))}
      </nav>
      <div className="px-3 py-4 border-t border-glass-border">
        {open && user?.username && (
          <div className="flex items-center gap-2.5 px-2 mb-2">
            <div className="w-8 h-8 rounded-full bg-gradient-to-br from-tech-400 to-cyber-500 flex items-center justify-center text-white text-xs font-bold flex-shrink-0">
              {(user.displayName || user.username || 'A').charAt(0)}
            </div>
            <div className="min-w-0"><p className="text-xs text-ink-100 truncate">{user.displayName || user.username}</p><p className="text-[10px] text-ink-500">管理员</p></div>
          </div>
        )}
        <button onClick={handleLogout} className="w-full flex items-center gap-2.5 px-3 py-2 rounded-lg text-sm text-ink-400 hover:text-cinnabar-400 hover:bg-cinnabar-500/5 transition-all">
          <LogOut className="w-4 h-4 flex-shrink-0" />{open && <span>退出登录</span>}
        </button>
      </div>
    </aside>
  );
}
