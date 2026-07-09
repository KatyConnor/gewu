import { useState, useMemo, useEffect } from 'react'
import { useApp } from '../../stores/appStore'
import FileTreeNode from '../FileTreeNode'

interface FileEntry {
  name: string
  path: string
  isDirectory: boolean
  children?: FileEntry[]
}

export default function ProjectPanel() {
  const app = useApp()
  const [fileTree, setFileTree] = useState<FileEntry[]>([])
  const [loading, setLoading] = useState(false)
  const [selectedFilePath, setSelectedFilePath] = useState('')

  const sortedRootNodes = useMemo(() => {
    return [...fileTree].sort((a, b) => {
      if (a.isDirectory && !b.isDirectory) return -1
      if (!a.isDirectory && b.isDirectory) return 1
      return a.name.localeCompare(b.name)
    })
  }, [fileTree])

  const selectedFileName = selectedFilePath ? selectedFilePath.split('/').pop() || '' : ''

  useEffect(() => {
    if (app.currentProject) {
      setLoading(true)
      // TODO: call fsApi.list
      setTimeout(() => {
        setFileTree([])
        setLoading(false)
      }, 300)
    }
  }, [app.currentProject])

  function onSelect(path: string) {
    setSelectedFilePath(path)
    app.activeFile = path
  }

  function onOpenFile(path: string) {
    app.openFile(path)
  }

  if (!app.currentProject) {
    return (
      <div style={styles.panel}>
        <div style={{ ...styles.header, ...styles.noProject }} onClick={() => app.showProjectDialog = true}>
          <svg width={18} height={18} viewBox="0 0 18 18" fill="none">
            <path d="M3 3C3 2.44772 3.44772 2 4 2H8L10 4.5H14C14.5523 4.5 15 4.94772 15 5.5V14C15 14.5523 14.5523 15 14 15H4C3.44772 15 3 14.5523 3 14V3Z" fill="#90A4AE" />
            <path d="M3 6H15V14C15 14.5523 14.5523 15 14 15H4C3.44772 15 3 14.5523 3 14V6Z" fill="#B0BEC5" />
            <path d="M8 9V13M6 11H10" stroke="#FFF" strokeWidth={1.5} strokeLinecap="round" />
          </svg>
          <span style={styles.projectName}>打开项目</span>
        </div>
      </div>
    )
  }

  return (
    <div style={styles.panel}>
      <div style={styles.header}>
        <div style={styles.projectInfo}>
          <svg width={18} height={18} viewBox="0 0 18 18" fill="none">
            <path d="M3 3C3 2.44772 3.44772 2 4 2H8L10 4.5H14C14.5523 4.5 15 4.94772 15 5.5V14C15 14.5523 14.5523 15 14 15H4C3.44772 15 3 14.5523 3 14V3Z" fill="#FFC107" />
            <path d="M3 6H15V14C15 14.5523 14.5523 15 14 15H4C3.44772 15 3 14.5523 3 14V6Z" fill="#FFD54F" />
          </svg>
          <div style={styles.projectText}>
            <span style={styles.projectName}>{app.currentProject.name}</span>
            <span style={styles.projectPathText} title={app.currentProject.path}>{app.currentProject.path}</span>
          </div>
        </div>
        <button style={styles.btnClose} onClick={() => app.closeProject()} title="关闭项目">
          <svg width={14} height={14} viewBox="0 0 14 14" fill="none">
            <path d="M3 3L11 11M11 3L3 11" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" />
          </svg>
        </button>
      </div>

      <div style={styles.sectionHeader}>
        <span style={styles.sectionTitle}>项目文件</span>
      </div>

      <div style={styles.fileTree}>
        {loading ? (
          <div style={styles.loadingState}>
            <span style={styles.spinner} />
            <span style={styles.loadingText}>加载文件结构...</span>
          </div>
        ) : sortedRootNodes.length === 0 ? (
          <div style={styles.emptyState}>
            <span style={styles.emptyText}>项目为空</span>
          </div>
        ) : (
          sortedRootNodes.map(node => (
            <FileTreeNode key={node.path} node={node as any} depth={0} onSelect={path => { onSelect(path); onOpenFile(path) }} />
          ))
        )}
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  panel: { display: 'flex', flexDirection: 'column', height: '100%', background: 'var(--bg-primary)' },
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px 16px', borderBottom: '1px solid var(--border)', background: 'var(--bg-secondary)' },
  noProject: { cursor: 'pointer', justifyContent: 'center', gap: 8 },
  projectInfo: { display: 'flex', alignItems: 'center', gap: 10, minWidth: 0, flex: 1 },
  projectText: { display: 'flex', flexDirection: 'column', minWidth: 0, flex: 1, gap: 2 },
  projectName: { fontSize: 13, fontWeight: 600, color: 'var(--text-primary)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' },
  projectPathText: { fontSize: 11, color: 'var(--text-muted)', fontFamily: "'JetBrains Mono', monospace", whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' },
  btnClose: { background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer', padding: 6, borderRadius: 4, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 },
  sectionHeader: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 12px', borderBottom: '1px solid var(--border)', background: 'var(--bg-secondary)' },
  sectionTitle: { fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 600 },
  fileTree: { flex: 1, overflowY: 'auto', overflowX: 'hidden', padding: '4px 0' },
  loadingState: { display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '32px 16px', gap: 12 },
  spinner: { width: 20, height: 20, border: '2px solid var(--text-muted)', borderTopColor: 'transparent', borderRadius: '50%', animation: 'spin 0.8s linear infinite' },
  loadingText: { fontSize: 12, color: 'var(--text-muted)' },
  emptyState: { display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '32px 16px', gap: 12 },
  emptyText: { fontSize: 13, color: 'var(--text-muted)' },
}
