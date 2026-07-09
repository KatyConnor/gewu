import { useState } from 'react'

interface FileNode {
  name: string
  path: string
  type: 'file' | 'directory'
  children?: FileNode[]
}

interface Props {
  node: FileNode
  depth: number
  onSelect?: (path: string) => void
}

export default function FileTreeNode({ node, depth, onSelect }: Props) {
  const [expanded, setExpanded] = useState(false)

  function handleClick() {
    if (node.type === 'directory') {
      setExpanded(!expanded)
    } else {
      onSelect?.(node.path)
    }
  }

  return (
    <div>
      <div
        style={{ ...styles.node, paddingLeft: depth * 12 + 8 }}
        onClick={handleClick}
      >
        {node.type === 'directory' ? (
          <span style={{ ...styles.arrow, transform: expanded ? 'rotate(90deg)' : undefined }}>▶</span>
        ) : (
          <span style={styles.arrowSpace} />
        )}
        {node.type === 'directory' ? (
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} style={{ ...styles.nodeIcon, color: 'var(--accent)' }}>
            <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
          </svg>
        ) : (
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} style={{ ...styles.nodeIcon, color: 'var(--text-muted)' }}>
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
          </svg>
        )}
        <span style={styles.name}>{node.name}</span>
      </div>
      {expanded && node.children && (
        <div>
          {node.children.map(child => (
            <FileTreeNode key={child.path} node={child} depth={depth + 1} onSelect={onSelect} />
          ))}
        </div>
      )}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  node: {
    display: 'flex', alignItems: 'center', gap: 4, padding: '3px 8px',
    cursor: 'pointer', color: 'var(--text-secondary)', fontSize: 13,
    userSelect: 'none',
  },
  arrow: {
    fontSize: 8, transition: 'transform 0.15s', width: 12,
    textAlign: 'center', color: 'var(--text-muted)',
  },
  arrowSpace: { width: 12 },
  nodeIcon: { width: 14, height: 14, flexShrink: 0 },
  name: { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' as const },
}
