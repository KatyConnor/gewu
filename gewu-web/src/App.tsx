import { lazy, Suspense, useEffect } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { Spin } from 'antd'
import { useApp } from './stores/appStore'
import Titlebar from './components/layout/Titlebar'
import SidebarRail from './components/layout/SidebarRail'
import SidebarPanel from './components/layout/SidebarPanel'
import SettingsDialog from './components/settings/SettingsDialog'
import CommandPalette from './components/CommandPalette'
import PermissionDialog from './components/PermissionDialog'
import NotificationToast from './components/NotificationToast'
import ProjectDialog from './components/project/ProjectDialog'
import Login from './pages/Login'

const Dashboard = lazy(() => import('./pages/Dashboard'))
const HomeView = lazy(() => import('./pages/HomeView'))
const SessionView = lazy(() => import('./pages/SessionView'))
const ProjectList = lazy(() => import('./pages/ProjectList'))
const SessionList = lazy(() => import('./pages/SessionList'))
const SessionDetail = lazy(() => import('./pages/SessionDetail'))
const AgentList = lazy(() => import('./pages/AgentList'))
const AgentDetail = lazy(() => import('./pages/AgentDetail'))
const WorkflowList = lazy(() => import('./pages/WorkflowList'))
const WorkflowDesigner = lazy(() => import('./pages/WorkflowDesigner'))
const WorkflowInstanceDetail = lazy(() => import('./pages/WorkflowInstanceDetail'))
const AiChat = lazy(() => import('./pages/AiChat'))
const SandboxList = lazy(() => import('./pages/SandboxList'))

const fallback = (
  <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '100%' }}>
    <Spin size="large" />
  </div>
)

function AppLayout() {
  const { showSidebarPanel, showSessionPanel, showReviewPanel, showTestPanel, showSettings, showCommandPalette, showProjectDialog, initTheme, checkHealth, loadSessions, loadAgents, loadProviders, loadConfig } = useApp()

  useEffect(() => {
    initTheme()
    checkHealth()
    loadSessions()
    loadAgents()
    loadProviders()
    loadConfig()

    const handleKeydown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault()
        useApp.getState().toggleCommandPalette()
      }
      if (e.key === 'Escape') {
        if (useApp.getState().showCommandPalette) {
          useApp.getState().toggleCommandPalette()
        }
        if (useApp.getState().showSettings) {
          useApp.getState().toggleSettings()
        }
      }
    }

    document.addEventListener('keydown', handleKeydown)
    return () => document.removeEventListener('keydown', handleKeydown)
  }, [])

  const showPanel = showSidebarPanel || showSessionPanel || showReviewPanel || showTestPanel

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden' }}>
      <Titlebar />
      <div style={{ flex: 1, display: 'flex', minHeight: 0 }}>
        <SidebarRail />
        {showPanel && <SidebarPanel />}
        <main style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0, background: 'var(--bg-primary)' }}>
          <Suspense fallback={fallback}>
            <Routes>
              <Route index element={<HomeView />} />
              <Route path="session/:id" element={<SessionView />} />
              <Route path="projects" element={<ProjectList />} />
              <Route path="sessions" element={<SessionList />} />
              <Route path="sessions/:sessionId" element={<SessionDetail />} />
              <Route path="agents" element={<AgentList />} />
              <Route path="agents/:agentId" element={<AgentDetail />} />
              <Route path="workflows" element={<WorkflowList />} />
              <Route path="workflows/new" element={<WorkflowDesigner />} />
              <Route path="workflows/:workflowId/design" element={<WorkflowDesigner />} />
              <Route path="workflows/instances/:instanceId" element={<WorkflowInstanceDetail />} />
              <Route path="ai-chat" element={<AiChat />} />
              <Route path="sandboxes" element={<SandboxList />} />
              <Route path="settings" element={<Dashboard />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </Suspense>
        </main>
      </div>
      {showSettings && <SettingsDialog />}
      {showCommandPalette && <CommandPalette />}
      {showProjectDialog && <ProjectDialog />}
      <PermissionDialog />
      <NotificationToast />
    </div>
  )
}

function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/*" element={<AppLayout />} />
    </Routes>
  )
}

export default App
