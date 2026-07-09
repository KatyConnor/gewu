import { useEffect, useState } from 'react'
import { useApp } from '../stores/appStore'

interface ToastProps {
  id: number
  msg: string
  type: 'info' | 'success' | 'warning' | 'error'
  onDismiss: (id: number) => void
}

function Toast({ id, msg, type, onDismiss }: ToastProps) {
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    requestAnimationFrame(() => setVisible(true))
  }, [])

  function handleDismiss() {
    setVisible(false)
    setTimeout(() => onDismiss(id), 300)
  }

  const borderColors: Record<string, string> = {
    error: 'rgba(239, 107, 107, 0.3)',
    success: 'rgba(62, 207, 142, 0.3)',
    warning: 'rgba(240, 180, 41, 0.3)',
    info: 'var(--border)',
  }

  const bgColors: Record<string, string> = {
    error: 'var(--danger-bg)',
    success: 'var(--success-bg)',
    warning: 'var(--warning-bg)',
    info: 'var(--bg-elevated)',
  }

  return (
    <div
      style={{
        display: 'flex', alignItems: 'center', gap: 10, padding: '12px 16px',
        borderRadius: 'var(--radius-md)', fontSize: 13,
        background: bgColors[type] || bgColors.info,
        border: `1px solid ${borderColors[type] || borderColors.info}`,
        color: 'var(--text-primary)', minWidth: 220, maxWidth: 420,
        boxShadow: 'var(--shadow-lg)',
        opacity: visible ? 1 : 0,
        transform: visible ? 'translateY(0) scale(1)' : 'translateY(10px) scale(0.95)',
        transition: 'all 0.3s ease',
      }}
    >
      <span style={{ flex: 1 }}>{msg}</span>
      <button
        onClick={handleDismiss}
        style={{
          background: 'none', border: 'none', color: 'var(--text-muted)',
          cursor: 'pointer', fontSize: 16, padding: '2px 4px',
          borderRadius: 'var(--radius-sm)',
        }}
      >
        ×
      </button>
    </div>
  )
}

export default function NotificationToast() {
  const app = useApp()

  return (
    <div style={{
      position: 'fixed', bottom: 20, right: 20, zIndex: 200,
      display: 'flex', flexDirection: 'column', gap: 10,
    }}>
      {app.notifications.map(n => (
        <Toast
          key={n.id}
          id={n.id}
          msg={n.msg}
          type={n.type}
          onDismiss={app.removeNotification}
        />
      ))}
    </div>
  )
}
