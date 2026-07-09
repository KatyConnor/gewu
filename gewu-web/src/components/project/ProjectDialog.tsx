import { useState, useMemo, useEffect, useRef, useCallback } from 'react'
import { useApp } from '../../stores/appStore'

export default function ProjectDialog() {
  const app = useApp()
  const [showOpenDialog, setShowOpenDialog] = useState(false)
  const [openProjectPath, setOpenProjectPath] = useState('')
  const [showNewProject, setShowNewProject] = useState(false)
  const [newProjectName, setNewProjectName] = useState('')
  const [newProjectPath, setNewProjectPath] = useState('')
  const pathInputRef = useRef<HTMLInputElement>(null)

  const projectName = useMemo(() => {
    const path = openProjectPath.trim()
    if (!path) return ''
    const parts = path.replace(/\\/g, '/').split('/').filter(Boolean)
    return parts[parts.length - 1] || path
  }, [openProjectPath])

  function close() {
    app.showProjectDialog = false
    setShowOpenDialog(false)
    setOpenProjectPath('')
    setShowNewProject(false)
    setNewProjectName('')
    setNewProjectPath('')
  }

  function confirmOpenProject() {
    const path = openProjectPath.trim()
    if (!path) return
    const project = { name: projectName, path, createdAt: Date.now() }
    app.openProject(project)
    close()
  }

  function createProject() {
    if (!newProjectName.trim()) return
    const parentPath = newProjectPath.trim() || '/home'
    const project = {
      name: newProjectName.trim(),
      path: parentPath + '/' + newProjectName.trim(),
      createdAt: Date.now(),
    }
    app.openProject(project)
    close()
  }

  function selectRecentProject(project: any) {
    app.openProject(project)
    close()
  }

  if (!app.showProjectDialog) return null

  return (
    <>
      <div style={styles.overlay} onClick={close}>
        <div style={styles.dialog} onClick={e => e.stopPropagation()}>
          <div style={styles.dialogHeader}>
            <h3 style={styles.dialogTitle}>项目管理</h3>
            <button style={styles.btnClose} onClick={close}>✕</button>
          </div>
          <div style={styles.dialogBody}>
            <div style={styles.actionCard} onClick={() => setShowOpenDialog(true)}>
              <div style={styles.actionIcon}>📂</div>
              <div>
                <div style={styles.actionTitle}>打开项目</div>
                <div style={styles.actionDesc}>输入项目绝对路径打开本地目录</div>
              </div>
            </div>

            <div style={styles.actionCard} onClick={() => setShowNewProject(!showNewProject)}>
              <div style={styles.actionIcon}>➕</div>
              <div>
                <div style={styles.actionTitle}>新建项目</div>
                <div style={styles.actionDesc}>创建新的项目目录</div>
              </div>
            </div>

            {showNewProject && (
              <div style={styles.inlineForm}>
                <input value={newProjectName} onChange={e => setNewProjectName(e.target.value)} placeholder="项目名称" style={styles.input} />
                <input value={newProjectPath} onChange={e => setNewProjectPath(e.target.value)} placeholder="父目录路径，如 /home/user" style={styles.input} />
                <div style={styles.formActions}>
                  <button style={styles.btnCancel} onClick={() => setShowNewProject(false)}>取消</button>
                  <button style={styles.btnConfirm} onClick={createProject}>创建</button>
                </div>
              </div>
            )}

            <div style={styles.recentSection}>
              <div style={styles.sectionTitle}>🕐 最近打开项目</div>
              {app.recentProjects.length > 0 ? (
                <div style={styles.recentList}>
                  {app.recentProjects.map(project => (
                    <div key={project.path} style={styles.recentItem} onClick={() => selectRecentProject(project)}>
                      <span style={styles.projectName}>{project.name}</span>
                      <span style={styles.projectPath}>{project.path}</span>
                      <button
                        style={styles.btnRemove}
                        onClick={e => { e.stopPropagation(); app.removeRecentProject(project.path) }}
                        title="移除"
                      >✕</button>
                    </div>
                  ))}
                </div>
              ) : (
                <div style={styles.emptyRecent}>暂无最近项目</div>
              )}
            </div>
          </div>
        </div>
      </div>

      {showOpenDialog && (
        <div style={styles.overlay} onClick={() => setShowOpenDialog(false)}>
          <div style={{ ...styles.dialog, width: 520 }} onClick={e => e.stopPropagation()}>
            <div style={styles.dialogHeader}>
              <h3 style={styles.dialogTitle}>打开项目</h3>
              <button style={styles.btnClose} onClick={() => setShowOpenDialog(false)}>✕</button>
            </div>
            <div style={styles.dialogBody}>
              <p style={styles.formHint}>输入路径打开项目目录</p>
              <input
                ref={pathInputRef}
                value={openProjectPath}
                onChange={e => setOpenProjectPath(e.target.value)}
                placeholder="输入路径，如 /home/user"
                style={{ ...styles.input, fontFamily: "'JetBrains Mono', monospace", fontSize: 14, padding: '12px 14px' }}
              />
              {openProjectPath.trim() && projectName && (
                <div style={styles.pathPreview}>
                  <span style={styles.previewLabel}>项目名称:</span>
                  <span style={styles.previewName}>{projectName}</span>
                </div>
              )}
              <div style={styles.formActions}>
                <button style={styles.btnCancel} onClick={() => setShowOpenDialog(false)}>取消</button>
                <button
                  style={{ ...styles.btnConfirm, ...(!openProjectPath.trim() ? { opacity: 0.5, cursor: 'not-allowed' } : {}) }}
                  onClick={confirmOpenProject}
                  disabled={!openProjectPath.trim()}
                >打开项目</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  )
}

const styles: Record<string, React.CSSProperties> = {
  overlay: { position: 'fixed', inset: 0, background: 'rgba(0, 0, 0, 0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 },
  dialog: { background: 'var(--bg-primary)', borderRadius: 12, width: 400, maxHeight: '80vh', overflow: 'hidden', boxShadow: '0 20px 60px rgba(0, 0, 0, 0.3)' },
  dialogHeader: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '16px 20px', borderBottom: '1px solid var(--border)' },
  dialogTitle: { margin: 0, fontSize: 16, fontWeight: 600, color: 'var(--text-primary)' },
  btnClose: { background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', fontSize: 18, padding: '4px 8px', borderRadius: 4 },
  dialogBody: { padding: 20, overflowY: 'auto', maxHeight: 'calc(80vh - 60px)' },
  actionCard: { display: 'flex', alignItems: 'center', gap: 16, padding: 16, background: 'var(--bg-secondary)', borderRadius: 8, cursor: 'pointer', marginBottom: 12 },
  actionIcon: { fontSize: 24 },
  actionTitle: { fontSize: 14, fontWeight: 500, color: 'var(--text-primary)', marginBottom: 4 },
  actionDesc: { fontSize: 12, color: 'var(--text-secondary)' },
  inlineForm: { padding: 16, background: 'var(--bg-secondary)', borderRadius: 8, marginBottom: 12 },
  input: { width: '100%', padding: '10px 12px', border: '1px solid var(--border)', borderRadius: 6, background: 'var(--bg-primary)', color: 'var(--text-primary)', fontSize: 13, marginBottom: 8, outline: 'none', boxSizing: 'border-box' },
  formActions: { display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 },
  btnCancel: { padding: '10px 20px', borderRadius: 6, fontSize: 13, fontWeight: 500, cursor: 'pointer', background: 'var(--bg-tertiary)', color: 'var(--text-secondary)', border: 'none' },
  btnConfirm: { padding: '10px 20px', borderRadius: 6, fontSize: 13, fontWeight: 500, cursor: 'pointer', background: 'var(--accent, #6366f1)', color: 'white', border: 'none' },
  recentSection: { marginTop: 16 },
  sectionTitle: { fontSize: 12, color: 'var(--text-muted)', marginBottom: 12 },
  recentList: { display: 'flex', flexDirection: 'column', gap: 8 },
  recentItem: { display: 'flex', alignItems: 'center', gap: 12, padding: 12, background: 'var(--bg-secondary)', borderRadius: 8, cursor: 'pointer' },
  projectName: { fontSize: 13, fontWeight: 500, color: 'var(--text-primary)' },
  projectPath: { flex: 1, fontSize: 11, color: 'var(--text-muted)', fontFamily: "'JetBrains Mono', monospace", overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
  btnRemove: { background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer', padding: '4px 8px', borderRadius: 4, fontSize: 12 },
  emptyRecent: { padding: 24, textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 },
  formHint: { fontSize: 13, color: 'var(--text-secondary)', marginBottom: 16 },
  pathPreview: { display: 'flex', alignItems: 'center', gap: 8, marginTop: 12, padding: '10px 14px', background: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: 6 },
  previewLabel: { fontSize: 12, color: 'var(--text-secondary)' },
  previewName: { fontSize: 13, fontWeight: 600, color: '#166534' },
}
