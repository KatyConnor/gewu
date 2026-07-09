import { useMemo } from 'react'

interface Tab {
  id: string
  path: string
  name: string
  modified: boolean
  language?: string
}

interface Props {
  tabs: Tab[]
  activeTabId: string | null
  onSelect: (id: string) => void
  onClose: (id: string) => void
  onCloseAll: () => void
  onCloseOthers: (id: string) => void
}

export default function EditorHeader({ tabs, activeTabId, onSelect, onClose, onCloseAll, onCloseOthers }: Props) {
  function getLanguageColor(lang?: string): string {
    const colors: Record<string, string> = {
      vue: '#42B883', javascript: '#F7DF1E', typescript: '#3178C6',
      html: '#E34F26', css: '#264DE4', json: '#78909C',
      python: '#3776AB', java: '#F89820', go: '#00ADD8', rust: '#DEA584',
    }
    return colors[lang || ''] || '#90A4AE'
  }

  return (
    <div style={styles.header}>
      <div style={styles.tabsContainer}>
        {tabs.map(tab => (
          <div
            key={tab.id}
            style={{
              ...styles.tab,
              ...(tab.id === activeTabId ? styles.tabActive : {}),
            }}
            onClick={() => onSelect(tab.id)}
            onMouseDown={e => { if (e.button === 1) { e.preventDefault(); onClose(tab.id) } }}
          >
            <span style={{ ...styles.tabIcon, color: getLanguageColor(tab.language) }}>●</span>
            <span style={{ ...styles.tabName, ...(tab.id === activeTabId ? styles.tabNameActive : {}) }} title={tab.path}>{tab.name}</span>
            {tab.modified && <span style={styles.tabModified}>●</span>}
            <button
              style={styles.tabClose}
              onClick={e => { e.stopPropagation(); onClose(tab.id) }}
              title="关闭"
            >
              <svg width={12} height={12} viewBox="0 0 12 12" fill="none">
                <path d="M3 3L9 9M9 3L3 9" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" />
              </svg>
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  header: { display: 'flex', alignItems: 'center', height: 35, background: 'var(--tab-bg, #2d2d2d)', borderBottom: '1px solid var(--border, #252526)', userSelect: 'none' },
  tabsContainer: { flex: 1, display: 'flex', overflowX: 'auto', overflowY: 'hidden' },
  tab: { display: 'flex', alignItems: 'center', gap: 6, height: '100%', padding: '0 12px', background: 'var(--tab-bg, #2d2d2d)', borderRight: '1px solid var(--border, #252526)', cursor: 'pointer', whiteSpace: 'nowrap', minWidth: 0, maxWidth: 200 },
  tabActive: { background: 'var(--tab-bg-active, #1e1e1e)', borderBottom: '2px solid var(--accent, #007acc)' },
  tabIcon: { display: 'flex', alignItems: 'center', flexShrink: 0, fontSize: 10 },
  tabName: { fontSize: 13, color: 'var(--tab-text, #969696)', overflow: 'hidden', textOverflow: 'ellipsis' },
  tabNameActive: { color: 'var(--tab-text-active, #ffffff)' },
  tabModified: { color: 'var(--accent, #007acc)', fontSize: 18, lineHeight: 1, marginLeft: 4 },
  tabClose: { display: 'flex', alignItems: 'center', justifyContent: 'center', width: 18, height: 18, padding: 0, marginLeft: 4, background: 'transparent', border: 'none', borderRadius: 4, color: 'var(--text-muted, #666)', cursor: 'pointer', opacity: 0, transition: 'opacity 0.1s' },
}
