import React, { useCallback } from 'react'
import { Select, Button, Tooltip } from 'antd'
import { useApp } from '../../stores/appStore'
import { useChat } from '../../stores/chatStore'

const AGENTS = [
  { value: 'build', label: 'Build' },
  { value: 'plan', label: 'Plan' },
  { value: 'explore', label: 'Explore' },
]

export default function ChatHeader() {
  const currentSession = useApp((s) => s.currentSession)
  const currentSessionId = useApp((s) => s.currentSessionId)
  const selectedAgent = useChat((s) => s.selectedAgent)
  const isStreaming = useChat((s) => s.isStreaming)
  const setSelectedAgent = useChat((s) => s.setSelectedAgent)
  const abortSession = useApp((s) => s.abortSession)

  const handleAbort = useCallback(() => {
    if (currentSessionId) {
      abortSession(currentSessionId)
    }
  }, [currentSessionId, abortSession])

  return (
    <div style={styles.header}>
      <div style={styles.headerLeft}>
        <h3 style={styles.sessionTitle}>{currentSession?.title || '会话'}</h3>
        <span style={styles.sessionId}>{currentSessionId}</span>
      </div>
      <div style={styles.headerRight}>
        <Select
          size="small"
          value={selectedAgent}
          onChange={setSelectedAgent}
          options={AGENTS}
          style={{ width: 100 }}
        />
        <Tooltip title="中止">
          <Button
            size="small"
            disabled={!isStreaming}
            onClick={handleAbort}
            style={styles.abortBtn}
          >
            ■
          </Button>
        </Tooltip>
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  header: {
    padding: '12px 24px',
    borderBottom: '1px solid var(--border)',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    background: 'var(--bg-main)',
  },
  headerLeft: {
    display: 'flex',
    alignItems: 'baseline',
    gap: 10,
  },
  sessionTitle: {
    fontSize: 16,
    fontWeight: 600,
    color: 'var(--text-primary)',
    margin: 0,
  },
  sessionId: {
    fontSize: 11,
    color: 'var(--text-muted)',
    fontFamily: 'monospace',
  },
  headerRight: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
  },
  abortBtn: {
    width: 32,
    height: 32,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: 12,
  },
}
