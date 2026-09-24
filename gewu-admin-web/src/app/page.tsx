'use client';
import { useState, useEffect } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { RootState, setPage } from '@/store';
import { isAuthenticated } from '@/lib/token';
import Sidebar from '@/components/layout/Sidebar';
import UserManagePage from '@/components/pages/UserManagePage';
import RoleManagePage from '@/components/pages/RoleManagePage';
import MenuManagePage from '@/components/pages/MenuManagePage';
import OrgManagePage from '@/components/pages/OrgManagePage';
import SkillAuditPage from '@/components/pages/SkillAuditPage';
import QuotaManagePage from '@/components/pages/QuotaManagePage';
import SandboxAuditPage from '@/components/pages/SandboxAuditPage';
import DashboardPage from '@/components/pages/AdminDashboardPage';
import LoginPage from '@/components/pages/LoginPage';

/** 后台管理端页面注册表 */
const pages: Record<string, React.ComponentType> = {
  login: LoginPage,
  dashboard: DashboardPage,
  'user-manage': UserManagePage,
  'role-manage': RoleManagePage,
  'menu-manage': MenuManagePage,
  'org-manage': OrgManagePage,
  'skill-audit': SkillAuditPage,
  'quota-manage': QuotaManagePage,
  'sandbox-audit': SandboxAuditPage,
};

export default function Home() {
  const dispatch = useDispatch();
  const currentPage = useSelector((s: RootState) => s.app.currentPage);
  const isAuthed = useSelector((s: RootState) => s.app.isAuthenticated);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    setHydrated(true);
    if (!isAuthed || !isAuthenticated()) {
      dispatch(setPage('login'));
    }
  }, [isAuthed, dispatch]);

  if (!hydrated) {
    return (
      <div className="min-h-screen flex items-center justify-center" style={{ background: '#081211' }}>
        <div className="w-6 h-6 border-2 border-tech-400 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  const PageComponent = pages[currentPage] || DashboardPage;
  return (
    <div className="flex h-screen w-full main-bg">
      <Sidebar />
      <main className="flex-1 overflow-y-auto scrollbar-thin p-8">
        <PageComponent />
      </main>
    </div>
  );
}
