'use client';
import { useState, useEffect } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { RootState, setPage, clearAuth } from '@/store';
import { isAuthenticated, isTokenValid } from '@/lib/token';
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
import AppSystemManagePage from '@/components/pages/AppSystemManagePage';
import ProjectManagePage from '@/components/pages/ProjectManagePage';
import RequirementManagePage from '@/components/pages/RequirementManagePage';

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
  'appsystem-manage': AppSystemManagePage,
  'project-manage': ProjectManagePage,
  'requirement-manage': RequirementManagePage,
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

  // 空闲过期主动检测：令牌在无 API 调用期间自然过期时，立即切换到登录页
  useEffect(() => {
    if (!isAuthed) return;
    const timer = setInterval(() => {
      if (!isTokenValid()) {
        dispatch(clearAuth());
      }
    }, 30_000);
    return () => clearInterval(timer);
  }, [isAuthed, dispatch]);

  if (!hydrated) {
    return (
      <div className="min-h-screen flex items-center justify-center" style={{ background: '#081211' }}>
        <div className="w-6 h-6 border-2 border-tech-400 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  const PageComponent = pages[currentPage] || DashboardPage;

  // 登录页独立全屏呈现，不套后台壳（侧栏）——含会话过期后切换到登录态的场景
  if (!isAuthed || currentPage === 'login') {
    return <PageComponent />;
  }

  return (
    <div className="flex h-screen w-full main-bg">
      <Sidebar />
      <main className="flex-1 overflow-y-auto scrollbar-thin p-8">
        <PageComponent />
      </main>
    </div>
  );
}
