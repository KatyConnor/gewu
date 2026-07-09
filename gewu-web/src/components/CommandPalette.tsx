import { useState, useEffect, useRef, useMemo } from 'react'
import { useApp } from '../stores/appStore'

interface Command {
  id: string
  name: string
  icon: string
  shortcut?: string
  action: () => void
}

export default function CommandPalette() {
  const app = useApp()
  const [query, setQuery] = useState('')
  const [selectedIndex, setSelectedIndex] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)

  const commands: Command[] = useMemo(() => [
    { id: 'new-session', name: '新建会话', icon: '📄', action: () => {} },
    { id: 'go-home', name: '返回主页', icon: '🏠', shortcut: 'Ctrl+H', action: () => app.clearCurrent() },
    { id: 'toggle-sidebar', name: '切换侧边栏', icon: '📁', shortcut: 'Ctrl+/', action: () => app.toggleSidebarPanel() },
    { id: 'toggle-terminal', name: '终端面板', icon: '⌨️', shortcut: 'Ctrl+\\', action: () => app.showTerminal = !app.showTerminal },
    { id: 'settings', name: '设置', icon: '⚙️', shortcut: 'Ctrl+,', action: () => app.showSettings = true },
    { id: 'toggle-theme', name: '切换主题', icon: '🎨', action: () => app.toggleTheme() },
    { id: 'search-sessions', name: '搜索会话', icon: '🔍', action: () => { app.sidePanelTab = 'sessions'; app.showSidebarPanel = true } },
  ], [app])

  const filteredCommands = useMemo(() => {
    if (!query) return commands
    const q = query.toLowerCase()
    return commands.filter(c => c.name.toLowerCase().includes(q) || c.id.includes(q))
  }, [query, commands])

  useEffect(() => {
    if (app.showCommandPalette) {
      setQuery('')
      setSelectedIndex(0)
      setTimeout(() => inputRef.current?.focus(), 0)
    }
  }, [app.showCommandPalette])

  useEffect(() => {
    setSelectedIndex(0)
  }, [query])

  function moveSelection(delta: number) {
    setSelectedIndex(prev => Math.max(0, Math.min(filteredCommands.length - 1, prev + delta)))
  }

  function execute(cmd: Command) {
    app.showCommandPalette = false
    cmd.action()
  }

  function executeSelected() {
    const cmd = filteredCommands[selectedIndex]
    if (cmd) execute(cmd)
  }

  if (!app.showCommandPalette) return null

  return (
    <div style={styles.overlay} onClick={() => app.showCommandPalette = false}>
      <div style={styles.dialog} onClick={e => e.stopPropagation()}>
        <div style={styles.inputRow}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} style={styles.searchIcon}>
            <circle cx={11} cy={11} r={8} /><line x1={21} y1={21} x2={16.65} y2={16.65} />
          </svg>
          <input
            ref={inputRef}
            value={query}
            onChange={e => setQuery(e.target.value)}
            style={styles.input}
            placeholder="搜索命令..."
            onKeyDown={e => {
              if (e.key === 'Escape') app.showCommandPalette = false
              if (e.key === 'ArrowDown') { e.preventDefault(); moveSelection(1) }
              if (e.key === 'ArrowUp') { e.preventDefault(); moveSelection(-1) }
              if (e.key === 'Enter') executeSelected()
            }}
          />
        </div>
        <div style={styles.list}>
          {filteredCommands.map((cmd, i) => (
            <div
              key={cmd.id}
              style={{
                ...styles.item,
                ...(i === selectedIndex ? styles.itemSelected : {}),
              }}
              onClick={() => execute(cmd)}
              onMouseEnter={() => setSelectedIndex(i)}
            >
              <span style={styles.icon}>{cmd.icon}</span>
              <span style={styles.name}>{cmd.name}</span>
              {cmd.shortcut && <kbd style={styles.shortcut}>{cmd.shortcut}</kbd>}
            </div>
          ))}
          {filteredCommands.length === 0 && (
            <div style={styles.empty}>无匹配命令</div>
          )}
        </div>
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  overlay: {
    position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)',
    display: 'flex', justifyContent: 'center', paddingTop: 120, zIndex: 100,
  },
  dialog: {
    background: 'var(--bg-secondary)', border: '1px solid var(--border)',
    borderRadius: 12, width: 480, maxHeight: 400,
    display: 'flex', flexDirection: 'column', overflow: 'hidden',
  },
  inputRow: {
    display: 'flex', alignItems: 'center', padding: '12px 16px',
    borderBottom: '1px solid var(--border)', gap: 8,
  },
  searchIcon: { width: 18, height: 18, color: 'var(--text-muted)', flexShrink: 0 },
  input: {
    flex: 1, background: 'none', border: 'none', color: 'var(--text-primary)',
    fontSize: 15, outline: 'none',
  },
  list: { flex: 1, overflowY: 'auto', padding: 8 },
  item: {
    display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px',
    borderRadius: 8, cursor: 'pointer', color: 'var(--text-secondary)', fontSize: 14,
  },
  itemSelected: { background: 'var(--bg-tertiary)', color: 'var(--text-primary)' },
  icon: { width: 20, textAlign: 'center' },
  name: { flex: 1 },
  shortcut: {
    background: 'var(--bg-primary)', border: '1px solid var(--border)', borderRadius: 4,
    padding: '2px 6px', fontSize: 11, fontFamily: 'monospace', color: 'var(--text-muted)',
  },
  empty: { textAlign: 'center', color: 'var(--text-muted)', padding: 20, fontSize: 13 },
}
