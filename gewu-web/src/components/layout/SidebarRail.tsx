import React from 'react'
import { useApp } from '../../stores/appStore'

const iconStyle: React.CSSProperties = { width: 20, height: 20 }

export default function SidebarRail() {
  const app = useApp()

  const goHome = () => {
    app.clearCurrent()
    app.closeAllPanels()
  }

  const handleSessionClick = () => {
    app.toggleSessionPanel()
  }

  const handleProjectClick = () => {
    app.set('showProjectDialog', true)
  }

  const handleReviewClick = () => {
    app.toggleReviewPanel()
  }

  const handleTestClick = () => {
    app.toggleTestPanel()
  }

  const railBtn = (active: boolean): React.CSSProperties => ({
    background: 'none',
    border: 'none',
    color: active ? 'var(--accent)' : 'var(--text-secondary)',
    cursor: 'pointer',
    padding: 10,
    borderRadius: 'var(--radius-md)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    transition: 'all var(--transition-fast)',
    position: 'relative',
    ...(active ? { background: 'var(--accent-muted, rgba(99,102,241,0.15))' } : {}),
  })

  const indicator: React.CSSProperties = {
    content: '',
    position: 'absolute',
    left: 0,
    top: '50%',
    transform: 'translateY(-50%)',
    width: 3,
    height: 20,
    background: 'var(--accent)',
    borderRadius: '0 2px 2px 0',
  }

  return (
    <nav style={{
      width: 56,
      background: 'var(--bg-secondary)',
      borderRight: '1px solid var(--border)',
      display: 'flex',
      flexDirection: 'column',
      justifyContent: 'space-between',
      alignItems: 'center',
      padding: '14px 0',
      flexShrink: 0,
    }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'center' }}>
        {/* Home */}
        <button style={railBtn(false)} onClick={goHome} title="主页" data-testid="rail-home">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={iconStyle}>
            <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
            <polyline points="9 22 9 12 15 12 15 22" />
          </svg>
        </button>

        {/* Sessions */}
        <button style={railBtn(app.showSessionPanel)} onClick={handleSessionClick} title="会话" data-testid="rail-session">
          {app.showSessionPanel && <span style={indicator} />}
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={iconStyle}>
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
          </svg>
        </button>

        {/* Projects */}
        <button style={railBtn(!!app.currentProject)} onClick={handleProjectClick} title="项目" data-testid="rail-project">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={iconStyle}>
            <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
          </svg>
        </button>

        {/* Review */}
        <button style={railBtn(app.showReviewPanel)} onClick={handleReviewClick} title="代码审查" data-testid="rail-review">
          {app.showReviewPanel && <span style={indicator} />}
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={iconStyle}>
            <circle cx="11" cy="11" r="8" />
            <line x1="21" y1="21" x2="16.65" y2="16.65" />
            <line x1="11" y1="8" x2="11" y2="14" />
            <line x1="8" y1="11" x2="14" y2="11" />
          </svg>
        </button>

        {/* Tests */}
        <button style={railBtn(app.showTestPanel)} onClick={handleTestClick} title="自动化测试" data-testid="rail-test">
          {app.showTestPanel && <span style={indicator} />}
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={iconStyle}>
            <path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z" />
            <polyline points="14 2 14 8 20 8" />
            <line x1="16" y1="13" x2="8" y2="13" />
            <line x1="16" y1="17" x2="8" y2="17" />
            <polyline points="10 9 9 9 8 9" />
          </svg>
        </button>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'center' }}>
        {/* Settings */}
        <button
          style={railBtn(false)}
          onClick={() => app.set('showSettings', true)}
          title="设置"
          data-testid="rail-settings"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={iconStyle}>
            <circle cx="12" cy="12" r="3" />
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
          </svg>
        </button>
      </div>
    </nav>
  )
}
