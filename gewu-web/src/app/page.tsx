'use client';
import { useState, useEffect } from 'react';
import { useSelector, useDispatch } from 'react-redux';
import { RootState, setPage } from '@/store';
import Sidebar from '@/components/layout/Sidebar';
import Header from '@/components/layout/Header';
import LoginPage from '@/components/pages/LoginPage';
import DashboardPage from '@/components/pages/DashboardPage';
import ChatPage from '@/components/pages/ChatPage';
import ProjectsPage from '@/components/pages/ProjectsPage';
import SettingsPage from '@/components/pages/SettingsPage';
import AgentMarketPage from '@/components/pages/AgentMarketPage';
import AgentManagePage from '@/components/pages/AgentManagePage';
import MyAgentsPage from '@/components/pages/MyAgentsPage';
import SkillLibraryPage from '@/components/pages/SkillLibraryPage';
import MySkillsPage from '@/components/pages/MySkillsPage';
import SkillAuditPage from '@/components/pages/SkillAuditPage';
import UserManagePage from '@/components/pages/UserManagePage';
import RoleManagePage from '@/components/pages/RoleManagePage';
import AuditCenterPage from '@/components/pages/AuditCenterPage';
import PrototypePage from '@/components/pages/PrototypePage';
import RequirementsPage from '@/components/pages/RequirementsPage';
import RequirementDetailPage from '@/components/pages/RequirementDetailPage';
import WorkflowPage from '@/components/pages/WorkflowPage';
import OrchestrationPage from '@/components/pages/OrchestrationPage';
import UsagePage from '@/components/pages/UsagePage';
import SandboxPage from '@/components/pages/SandboxPage';
import McpServerPage from '@/components/pages/McpServerPage';
import MenuManagePage from '@/components/pages/MenuManagePage';
import OrgManagePage from '@/components/pages/OrgManagePage';
import WorkspacePage from '@/components/pages/WorkspacePage';
import DevWorkspacePage from '@/components/pages/DevWorkspacePage';

const pages: Record<string, React.ComponentType> = {
  login: LoginPage,
  dashboard: DashboardPage,
  chat: ChatPage,
  projects: ProjectsPage,
  settings: SettingsPage,
  'agent-market': AgentMarketPage,
  'my-agents': MyAgentsPage,
  'agent-manage': AgentManagePage,
  'skill-library': SkillLibraryPage,
  'my-skills': MySkillsPage,
  'skill-audit': SkillAuditPage,
  'user-manage': UserManagePage,
  'role-manage': RoleManagePage,
  'audit-center': AuditCenterPage,
  prototype: PrototypePage,
  requirements: RequirementsPage,
  workflow: WorkflowPage,
  orchestration: OrchestrationPage,
  usage: UsagePage,
  sandbox: SandboxPage,
  'mcp-server': McpServerPage,
  'menu-manage': MenuManagePage,
  'org-manage': OrgManagePage,
  workspace: WorkspacePage,
  'dev-workspace': DevWorkspacePage,
};

export default function Home() {
  const dispatch = useDispatch();
  const currentPage = useSelector((s: RootState) => s.app.currentPage);
  const isAuthenticated = useSelector((s: RootState) => s.app.isAuthenticated);
  const [hydrated, setHydrated] = useState(false);
  const [, forceUpdate] = useState(0);

  // 水合完成后根据真实认证状态动态调整页面路由
  useEffect(() => {
    setHydrated(true);
    if (!isAuthenticated) {
      dispatch(setPage('login'));
    }
  }, [isAuthenticated, dispatch]);

  // 监听 hash 变化，触发重渲染以支持需求详情页等 hash 路由
  useEffect(() => {
    const handler = () => forceUpdate(n => n + 1);
    window.addEventListener('hashchange', handler);
    return () => window.removeEventListener('hashchange', handler);
  }, []);

  // SSR 和首次 CSR 统一渲染无状态占位布局，消除服务端/客户端 HTML 差异
  if (!hydrated) {
    return (
      <div className="min-h-screen flex items-center justify-center" style={{ background: '#081211' }}>
        <div className="w-6 h-6 border-2 border-tech-400 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  const PageComponent = pages[currentPage] || DashboardPage;

  if (currentPage === 'login') {
    return <LoginPage />;
  }

  // 会话页全屏显示，隐藏侧边栏和顶部栏
  if (currentPage === 'chat') {
    return (
      <div className="min-h-screen relative" style={{ background: '#081211' }}>
        <ChatPage />
      </div>
    );
  }

  // 需求详情页特殊处理
  if (currentPage === 'requirements') {
    const hash = typeof window !== 'undefined' ? window.location.hash : '';
    const detailMatch = hash.match(/#\/requirements\/(\w+)/);
    if (detailMatch) {
      return (
        <div className="min-h-screen flex">
          <Sidebar />
          <main className="flex-1 ml-64 pt-20 pb-8 px-8 relative" style={{ background: 'radial-gradient(ellipse at 15% 30%, rgba(0,184,148,0.06) 0%, transparent 50%), radial-gradient(ellipse at 85% 15%, rgba(6,182,212,0.04) 0%, transparent 40%), radial-gradient(ellipse at 50% 85%, rgba(212,168,53,0.05) 0%, transparent 45%), linear-gradient(170deg, #081211 0%, #0a1a18 30%, #0c1e1c 60%, #081211 100%)' }}>
            <Header />
            <RequirementDetailPage
              requirementId={detailMatch[1]}
              onBack={() => { window.location.hash = '#/requirements'; }}
            />
          </main>
        </div>
      );
    }
  }

  return (
    <div className="min-h-screen flex">
      <Sidebar />
      <main className="flex-1 ml-64 pt-20 pb-8 px-8 relative" style={{ background: 'radial-gradient(ellipse at 15% 30%, rgba(0,184,148,0.06) 0%, transparent 50%), radial-gradient(ellipse at 85% 15%, rgba(6,182,212,0.04) 0%, transparent 40%), radial-gradient(ellipse at 50% 85%, rgba(212,168,53,0.05) 0%, transparent 45%), linear-gradient(170deg, #081211 0%, #0a1a18 30%, #0c1e1c 60%, #081211 100%)' }}>
        <Header />
        <PageComponent />
      </main>
    </div>
  );
}
