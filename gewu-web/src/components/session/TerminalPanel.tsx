import { useState, useRef, useEffect } from 'react'
import { useApp } from '../../stores/appStore'

export default function TerminalPanel() {
  const app = useApp()
  const [cmd, setCmd] = useState('')
  const [lines, setLines] = useState<string[]>(['欢迎使用格物致虚终端'])
  const [panelHeight] = useState(200)
  const terminalRef = useRef<HTMLDivElement>(null)

  let tabId = 1
  function addTab() {
    tabId++
    app.terminalTabs = [...app.terminalTabs, { id: tabId, title: `Terminal ${tabId}` }]
    app.activeTerminal = tabId
    setLines([])
  }

  function execCmd() {
    if (!cmd.trim()) return
    setLines(prev => [...prev, '$ ' + cmd])
    setCmd('')
  }

  useEffect(() => {
    if (terminalRef.current) {
      terminalRef.current.scrollTop = terminalRef.current.scrollHeight
    }
  }, [lines])

  return (
    <div style={{ ...styles.panel, height: panelHeight }}>
      <div style={styles.header}>
        <div style={styles.tabs}>
          {app.terminalTabs.map(tab => (
            <button
              key={tab.id}
              style={{ ...styles.tab, ...(tab.id === app.activeTerminal ? styles.tabActive : {}) }}
              onClick={() => app.activeTerminal = tab.id}
            >
              {tab.title}
            </button>
          ))}
          <button style={styles.tabAdd} onClick={addTab}>+</button>
        </div>
        <div style={styles.actions}>
          <button style={styles.actionBtn} onClick={() => app.showTerminal = false} title="关闭">×</button>
        </div>
      </div>
      <div style={styles.body} ref={terminalRef}>
        <div style={styles.output}>
          {lines.map((line, i) => (
            <div key={i} style={styles.line}>{line}</div>
          ))}
        </div>
      </div>
      <div style={styles.inputRow}>
        <span style={styles.prompt}>$</span>
        <input
          value={cmd}
          onChange={e => setCmd(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') execCmd() }}
          style={styles.input}
          placeholder="输入命令..."
        />
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  panel: { borderTop: '1px solid var(--border)', background: 'var(--bg-primary)', display: 'flex', flexDirection: 'column', flexShrink: 0 },
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '0 8px', borderBottom: '1px solid var(--border)', minHeight: 32 },
  tabs: { display: 'flex', gap: 2 },
  tab: { background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', padding: '6px 12px', fontSize: 12, borderBottom: '2px solid transparent' },
  tabActive: { color: 'var(--text-primary)', borderBottomColor: 'var(--text-primary)' },
  tabAdd: { background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer', padding: '6px 8px', fontSize: 12 },
  actions: { display: 'flex', gap: 4 },
  actionBtn: { background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer', padding: '4px 8px', borderRadius: 4, fontSize: 14 },
  body: { flex: 1, overflowY: 'auto', padding: '8px 12px', fontFamily: "'JetBrains Mono', monospace", fontSize: 13 },
  output: {},
  line: { color: 'var(--text-secondary)', lineHeight: 1.5, whiteSpace: 'pre-wrap' },
  inputRow: { display: 'flex', alignItems: 'center', padding: '6px 12px', borderTop: '1px solid var(--border)', gap: 8 },
  prompt: { color: 'var(--accent)', fontFamily: "'JetBrains Mono', monospace", fontSize: 13 },
  input: { flex: 1, background: 'none', border: 'none', color: 'var(--text-primary)', fontFamily: "'JetBrains Mono', monospace", fontSize: 13, outline: 'none' },
}
