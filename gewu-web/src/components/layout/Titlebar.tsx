import React from 'react'
import { useApp } from '../../stores/appStore'
import { useChat } from '../../stores/chatStore'

export default function Titlebar() {
  const app = useApp()
  const chat = useChat()

  const getStatusClass = (): string => {
    if (chat.isStreaming) return 'busy'
    const sid = app.currentSessionId
    if (!sid || !app.sessionStatus) return ''
    const s = app.sessionStatus[sid]
    if (!s) return ''
    return s.type || ''
  }

  const getStatusLabel = (): string => {
    if (chat.isStreaming) return '运行中'
    const sid = app.currentSessionId
    if (!sid || !app.sessionStatus) return ''
    const s = app.sessionStatus[sid]
    if (!s) return ''
    if (s.type === 'idle') return '空闲'
    if (s.type === 'busy') return '运行中'
    if (s.type === 'retry') return '重试中'
    return ''
  }

  const statusClass = getStatusClass()
  const statusLabel = getStatusLabel()

  return (
    <header style={{
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      height: 52, padding: '0 16px', background: 'var(--bg-secondary)',
      borderBottom: '1px solid var(--border)', userSelect: 'none',
      flexShrink: 0, gap: 16,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <button
          onClick={app.toggleSidebarPanel}
          title="切换侧边栏"
          style={{
            background: 'none', border: 'none', color: 'var(--text-secondary)',
            cursor: 'pointer', padding: 8, borderRadius: 'var(--radius-sm)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={{ width: 18, height: 18 }}>
            <rect x="3" y="3" width="18" height="18" rx="2" />
            <line x1="9" y1="3" x2="9" y2="21" />
          </svg>
        </button>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13 }}>
          <span style={{
            fontSize: 16, fontWeight: 500, color: 'var(--text-primary)',
          }}>
            格物<span style={{ color: 'var(--accent)', fontWeight: 700, opacity: 0.8, margin: '0 3px' }}>·</span>致虚
          </span>
          {app.currentSession && (
            <>
              <span style={{ color: 'var(--text-muted)' }}>/</span>
              <span style={{ color: 'var(--text-secondary)', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {app.currentSession?.title || '新会话'}
              </span>
            </>
          )}
        </div>
      </div>

      <div style={{ flex: 1, display: 'flex', justifyContent: 'center', minWidth: 0 }}>
        {/* Session tabs placeholder */}
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        {statusLabel && (
          <div style={{
            display: 'flex', alignItems: 'center', gap: 6, padding: '4px 10px',
            borderRadius: 'var(--radius-sm)', fontSize: 11,
            background: 'var(--bg-tertiary)',
            color: statusClass === 'busy' ? 'var(--warning, #f59e0b)' :
                   statusClass === 'idle' ? 'var(--success, #22c55e)' :
                   statusClass === 'retry' ? 'var(--danger, #ef4444)' : 'var(--text-muted)',
          }}>
            <span style={{
              width: 6, height: 6, borderRadius: '50%', flexShrink: 0,
              background: statusClass === 'busy' ? 'var(--warning, #f59e0b)' :
                          statusClass === 'idle' ? 'var(--success, #22c55e)' :
                          statusClass === 'retry' ? 'var(--danger, #ef4444)' : 'var(--text-muted)',
              animation: statusClass === 'busy' ? 'statusPulse 1.5s ease-in-out infinite' : undefined,
            }} />
            <span style={{ whiteSpace: 'nowrap' }}>{statusLabel}</span>
          </div>
        )}

        <div style={{ display: 'flex', alignItems: 'center', padding: 4 }} title={app.connected ? '已连接' : '未连接'}>
          <span style={{
            width: 8, height: 8, borderRadius: '50%',
            background: app.connected ? 'var(--success)' : 'var(--text-muted)',
            transition: 'background var(--transition-normal)',
            boxShadow: app.connected ? '0 0 8px var(--success)' : undefined,
          }} />
        </div>

        <button
          onClick={app.toggleTheme}
          title={app.theme === 'dark' ? '切换亮色' : '切换暗色'}
          style={{
            background: 'none', border: 'none', color: 'var(--text-secondary)',
            cursor: 'pointer', padding: 8, borderRadius: 'var(--radius-sm)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}
        >
          {app.theme === 'dark' ? (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={{ width: 18, height: 18 }}>
              <circle cx="12" cy="12" r="5" />
              <line x1="12" y1="1" x2="12" y2="3" />
              <line x1="12" y1="21" x2="12" y2="23" />
              <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
              <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
              <line x1="1" y1="12" x2="3" y2="12" />
              <line x1="21" y1="12" x2="23" y2="12" />
              <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
              <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
            </svg>
          ) : (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={{ width: 18, height: 18 }}>
              <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
            </svg>
          )}
        </button>

        <button
          onClick={() => app.set('showSettings', true)}
          title="设置"
          style={{
            background: 'none', border: 'none', color: 'var(--text-secondary)',
            cursor: 'pointer', padding: 8, borderRadius: 'var(--radius-sm)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={{ width: 18, height: 18 }}>
            <circle cx="12" cy="12" r="3" />
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
          </svg>
        </button>
      </div>
    </header>
  )
}
