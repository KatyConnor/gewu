import { useApp } from '../stores/appStore'

interface PermissionRequest {
  id: string
  sessionID: string
  tool: string
  patterns?: string[]
  input?: any
}

export default function PermissionDialog() {
  const app = useApp()
  const permission = app.permissionRequest as PermissionRequest | null

  async function respond(response: string) {
    if (!permission) return
    try {
      // TODO: call permissionApi.respond
      app.addNotification(
        response === 'deny' ? '已拒绝权限' : '已允许权限',
        response === 'deny' ? 'warning' : 'success'
      )
      app.permissionRequest = null
    } catch (e: any) {
      app.addNotification('权限响应失败: ' + e.message, 'error')
    }
  }

  if (!permission) return null

  return (
    <div style={styles.overlay}>
      <div style={styles.dialog}>
        <div style={styles.header}>
          <div style={styles.icon}>⚠️</div>
          <div style={styles.title}>工具调用需要授权</div>
        </div>
        <div style={styles.body}>
          <div style={styles.toolRow}>
            <span style={styles.label}>工具:</span>
            <span style={styles.toolName}>{permission.tool || '未知'}</span>
          </div>
          {permission.patterns && permission.patterns.length > 0 && (
            <div style={styles.patternsRow}>
              <span style={styles.label}>路径:</span>
              <div style={styles.patterns}>
                {permission.patterns.map((p, i) => (
                  <span key={i} style={styles.patternItem}>{p}</span>
                ))}
              </div>
            </div>
          )}
        </div>
        <div style={styles.actions}>
          <button style={styles.btnDeny} onClick={() => respond('deny')}>拒绝</button>
          <button style={styles.btnAllow} onClick={() => respond('allow')}>允许</button>
          <button style={styles.btnAllowAll} onClick={() => respond('allow_always')}>始终允许</button>
        </div>
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  overlay: {
    position: 'fixed', inset: 0, background: 'rgba(12, 14, 20, 0.7)',
    backdropFilter: 'blur(4px)', display: 'flex', alignItems: 'center',
    justifyContent: 'center', zIndex: 1000,
  },
  dialog: {
    background: 'var(--bg-secondary)', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-lg)', padding: 28, maxWidth: 500,
    width: '90%', boxShadow: 'var(--shadow-xl)',
  },
  header: { display: 'flex', alignItems: 'center', gap: 14, marginBottom: 18 },
  icon: { fontSize: 26 },
  title: { fontSize: 17, fontWeight: 600, color: 'var(--text-primary)' },
  body: { marginBottom: 24 },
  toolRow: { display: 'flex', gap: 10, marginBottom: 10 },
  label: { fontSize: 13, color: 'var(--text-muted)', flexShrink: 0 },
  toolName: { fontSize: 13, color: 'var(--accent)', fontWeight: 500 },
  patternsRow: { display: 'flex', gap: 10, flexDirection: 'column', marginTop: 8 },
  patterns: { display: 'flex', flexWrap: 'wrap', gap: 6 },
  patternItem: {
    background: 'var(--bg-primary)', border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm)', padding: '4px 10px', fontSize: 12,
    fontFamily: "'JetBrains Mono', monospace", color: 'var(--text-secondary)',
  },
  actions: { display: 'flex', gap: 10, justifyContent: 'flex-end' },
  btnDeny: {
    padding: '10px 18px', borderRadius: 'var(--radius-md)', fontSize: 13,
    fontWeight: 500, cursor: 'pointer', border: '1px solid var(--border)',
    background: 'var(--bg-tertiary)', color: 'var(--text-secondary)',
  },
  btnAllow: {
    padding: '10px 18px', borderRadius: 'var(--radius-md)', fontSize: 13,
    fontWeight: 500, cursor: 'pointer',
    background: 'linear-gradient(135deg, var(--accent) 0%, #2db87a 100%)',
    color: 'var(--text-inverse)', border: '1px solid var(--accent)',
  },
  btnAllowAll: {
    padding: '10px 18px', borderRadius: 'var(--radius-md)', fontSize: 13,
    fontWeight: 500, cursor: 'pointer',
    background: 'linear-gradient(135deg, var(--success) 0%, #22a962 100%)',
    color: 'var(--text-inverse)', border: '1px solid var(--success)',
  },
}
