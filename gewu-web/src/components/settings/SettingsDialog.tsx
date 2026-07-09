import { useState, useMemo, useEffect, useRef, useCallback } from 'react'
import { useApp } from '../../stores/appStore'

const soundOptions = [
  { value: '', label: '无' },
  { value: 'staplebops-01', label: 'Staple Bops 01' },
  { value: 'staplebops-02', label: 'Staple Bops 02' },
  { value: 'staplebops-03', label: 'Staple Bops 03' },
  { value: 'staplebops-04', label: 'Staple Bops 04' },
  { value: 'staplebops-05', label: 'Staple Bops 05' },
  { value: 'nope-01', label: 'Nope 01' },
  { value: 'nope-02', label: 'Nope 02' },
  { value: 'nope-03', label: 'Nope 03' },
  { value: 'alert-01', label: 'Alert 01' },
  { value: 'alert-02', label: 'Alert 02' },
]

const defaultShortcuts = [
  { id: 'palette', group: '通用', desc: '命令面板', bind: 'Ctrl+Shift+P' },
  { id: 'newSession', group: '通用', desc: '新会话', bind: 'Ctrl+N' },
  { id: 'toggleSidebar', group: '通用', desc: '切换侧边栏', bind: 'Ctrl+B' },
  { id: 'toggleTerminal', group: '通用', desc: '终端面板', bind: 'Ctrl+`' },
  { id: 'openSettings', group: '通用', desc: '设置', bind: 'Ctrl+,' },
  { id: 'sendMessage', group: '会话', desc: '发送消息', bind: 'Enter' },
  { id: 'newLine', group: '会话', desc: '换行', bind: 'Shift+Enter' },
  { id: 'stopStreaming', group: '会话', desc: '停止流式输出', bind: 'Escape' },
  { id: 'approvePermission', group: '会话', desc: '批准权限', bind: 'Y' },
  { id: 'rejectPermission', group: '会话', desc: '拒绝权限', bind: 'N' },
  { id: 'focusInput', group: '导航', desc: '聚焦输入框', bind: '/' },
  { id: 'goHome', group: '导航', desc: '返回主页', bind: 'Alt+Home' },
  { id: 'toggleTheme', group: '导航', desc: '切换主题', bind: 'Ctrl+Shift+T' },
  { id: 'switchModel', group: '模型与智能体', desc: '切换模型', bind: 'Ctrl+M' },
  { id: 'switchAgent', group: '模型与智能体', desc: '切换智能体', bind: 'Ctrl+Shift+A' },
  { id: 'terminalNew', group: '终端', desc: '新建终端', bind: 'Ctrl+Shift+`' },
  { id: 'terminalClear', group: '终端', desc: '清空终端', bind: 'Ctrl+K' },
]

type TabId = 'general' | 'shortcuts'

interface ToggleProps {
  checked: boolean
  onChange: () => void
}

function Toggle({ checked, onChange }: ToggleProps) {
  return (
    <button
      onClick={onChange}
      style={{
        position: 'relative', width: 40, height: 22, borderRadius: 11,
        background: checked ? 'var(--accent)' : 'var(--bg-tertiary)',
        border: `1px solid ${checked ? 'var(--accent)' : 'var(--border)'}`,
        cursor: 'pointer', padding: 0, flexShrink: 0, transition: 'background 0.2s',
      }}
    >
      <span style={{
        position: 'absolute', top: 2, left: checked ? 20 : 2,
        width: 16, height: 16, borderRadius: '50%', background: 'white',
        transition: 'left 0.2s',
      }} />
    </button>
  )
}

export default function SettingsDialog() {
  const app = useApp()
  const [activeTab, setActiveTab] = useState<TabId>('general')
  const [shortcutSearch, setShortcutSearch] = useState('')
  const [capturingId, setCapturingId] = useState<string | null>(null)
  const [shortcutOverrides, setShortcutOverrides] = useState<Record<string, string>>({ ...app.savedShortcuts })
  const captureHandlerRef = useRef<((e: KeyboardEvent) => void) | null>(null)

  useEffect(() => {
    return () => {
      if (captureHandlerRef.current) {
        document.removeEventListener('keydown', captureHandlerRef.current, true)
      }
    }
  }, [])

  const shortcutGroups = useMemo(() => {
    const all = defaultShortcuts.map(s => ({
      ...s,
      bind: shortcutOverrides[s.id] !== undefined ? shortcutOverrides[s.id] : s.bind
    }))
    const groups: Record<string, { name: string; items: typeof all }> = {}
    for (const s of all) {
      if (!groups[s.group]) groups[s.group] = { name: s.group, items: [] }
      groups[s.group].items.push(s)
    }
    return Object.values(groups)
  }, [shortcutOverrides])

  const filteredShortcutGroups = useMemo(() => {
    if (!shortcutSearch) return shortcutGroups
    const q = shortcutSearch.toLowerCase()
    return shortcutGroups
      .map(g => ({ ...g, items: g.items.filter(i => i.desc.toLowerCase().includes(q) || (shortcutOverrides[i.id] || i.bind).toLowerCase().includes(q)) }))
      .filter(g => g.items.length > 0)
  }, [shortcutSearch, shortcutGroups, shortcutOverrides])

  const hasOverrides = Object.keys(shortcutOverrides).length > 0

  function saveShortcuts() {
    app.savedShortcuts = { ...shortcutOverrides }
    app.persistSettings()
  }

  function resetShortcuts() {
    setShortcutOverrides({})
    setTimeout(() => {
      app.savedShortcuts = {}
      app.persistSettings()
    }, 0)
  }

  function startCapture(id: string) {
    setCapturingId(id)
    captureHandlerRef.current = (e: KeyboardEvent) => {
      e.preventDefault()
      e.stopPropagation()
      if (e.key === 'Escape') {
        setCapturingId(null)
        return
      }
      if (e.key === 'Backspace' || e.key === 'Delete') {
        setShortcutOverrides(prev => ({ ...prev, [id]: '' }))
        setCapturingId(null)
        setTimeout(saveShortcuts, 0)
        return
      }
      const parts: string[] = []
      if (e.ctrlKey || e.metaKey) parts.push('Ctrl')
      if (e.shiftKey) parts.push('Shift')
      if (e.altKey) parts.push('Alt')
      const key = e.key.length === 1 ? e.key.toUpperCase() : e.key
      if (!['Control', 'Shift', 'Alt', 'Meta'].includes(e.key)) {
        parts.push(key)
        const bind = parts.join('+')
        setShortcutOverrides(prev => ({ ...prev, [id]: bind }))
        setCapturingId(null)
        setTimeout(saveShortcuts, 0)
      }
    }
    document.addEventListener('keydown', captureHandlerRef.current, true)
  }

  function formatKey(key: string) {
    if (!key) return '未分配'
    return key
  }

  function getPreviewColor(t: any, key: string): string {
    const mode = app.theme === 'light' ? 'light' : 'dark'
    const vars = t[mode]
    if (!vars) return '#888'
    const map: Record<string, string> = { primary: '--accent', accent: '--info', success: '--success', error: '--danger' }
    return vars[map[key]] || '#888'
  }

  if (!app.showSettings) return null

  const sidebarTabs: { id: TabId; label: string }[] = [
    { id: 'general', label: '通用' },
    { id: 'shortcuts', label: '快捷键' },
  ]

  return (
    <div style={styles.overlay} onClick={() => app.showSettings = false}>
      <div style={styles.dialog} onClick={e => e.stopPropagation()}>
        <div style={styles.header}>
          <h2 style={styles.title}>设置</h2>
          <button style={styles.closeBtn} onClick={() => app.showSettings = false}>×</button>
        </div>
        <div style={styles.body}>
          <div style={styles.sidebar}>
            <div style={styles.sidebarSection}>
              <div style={styles.sectionLabel}>桌面</div>
              {sidebarTabs.map(tab => (
                <button
                  key={tab.id}
                  style={{ ...styles.sidebarTab, ...(activeTab === tab.id ? styles.sidebarTabActive : {}) }}
                  onClick={() => setActiveTab(tab.id)}
                >
                  {tab.label}
                </button>
              ))}
            </div>
            <div style={styles.sidebarFooter}>
              <span style={styles.appName}>格物·致虚</span>
              <span style={styles.appVersion}>v1.0.0</span>
            </div>
          </div>

          <div style={styles.content}>
            {activeTab === 'general' && (
              <>
                <div style={styles.tabHeader}>通用</div>
                <div style={styles.tabBody}>
                  <div style={styles.section}>
                    <div style={styles.sectionTitle}>对话</div>
                    <div style={styles.settingsList}>
                      <SettingRow title="OpenCode 服务器" desc="后端服务地址">
                        <input
                          value={app.serverUrl}
                          onChange={e => app.serverUrl = e.target.value}
                          onBlur={() => app.persistSettings()}
                          style={styles.textInput}
                          placeholder="http://127.0.0.1:4096"
                        />
                      </SettingRow>
                      <SettingRow title="自动接受权限" desc="自动批准文件读写和命令执行权限">
                        <Toggle checked={app.autoAcceptPermissions} onChange={() => { app.autoAcceptPermissions = !app.autoAcceptPermissions; app.persistSettings() }} />
                      </SettingRow>
                      <SettingRow title="Shell" desc="终端和 Bash 工具使用的默认 Shell">
                        <select
                          value={app.shell}
                          onChange={e => { app.shell = e.target.value; app.persistSettings() }}
                          style={styles.selectInput}
                        >
                          <option value="">自动</option>
                          <option value="bash">bash</option>
                          <option value="zsh">zsh</option>
                          <option value="fish">fish</option>
                          <option value="sh">sh</option>
                        </select>
                      </SettingRow>
                      <SettingRow title="显示模型推理摘要" desc="在消息中展示模型的思考过程">
                        <Toggle checked={app.showReasoning} onChange={() => { app.showReasoning = !app.showReasoning; app.persistSettings() }} />
                      </SettingRow>
                      <SettingRow title="默认展开 Shell 工具" desc="工具调用结果默认展开显示">
                        <Toggle checked={app.expandShellTools} onChange={() => { app.expandShellTools = !app.expandShellTools; app.persistSettings() }} />
                      </SettingRow>
                    </div>
                  </div>

                  <div style={styles.section}>
                    <div style={styles.sectionTitle}>外观</div>
                    <div style={styles.settingsList}>
                      <SettingRow title="色彩模式" desc="切换深色或浅色主题模式">
                        <div style={{ display: 'flex', gap: 4 }}>
                          <button
                            style={{ ...styles.modeBtn, ...(app.theme === 'dark' ? styles.modeBtnActive : {}) }}
                            onClick={() => app.setTheme(app.themeId, 'dark')}
                          >暗色</button>
                          <button
                            style={{ ...styles.modeBtn, ...(app.theme === 'light' ? styles.modeBtnActive : {}) }}
                            onClick={() => app.setTheme(app.themeId, 'light')}
                          >亮色</button>
                        </div>
                      </SettingRow>
                    </div>
                    <div style={styles.themeGrid}>
                      {app.allThemes.map(t => (
                        <button
                          key={t.id}
                          style={{ ...styles.themeCard, ...(app.themeId === t.id ? styles.themeCardActive : {}) }}
                          onClick={() => app.setTheme(t.id)}
                        >
                          <div style={styles.themePreview}>
                            <span style={{ ...styles.previewDot, background: getPreviewColor(t, 'primary') }} />
                            <span style={{ ...styles.previewDot, background: getPreviewColor(t, 'accent') }} />
                            <span style={{ ...styles.previewDot, background: getPreviewColor(t, 'success') }} />
                            <span style={{ ...styles.previewDot, background: getPreviewColor(t, 'error') }} />
                          </div>
                          <span style={styles.themeName}>{t.name}</span>
                        </button>
                      ))}
                    </div>
                    <div style={{ ...styles.settingsList, marginTop: 8 }}>
                      <SettingRow title="UI 字体" desc="界面文字字体">
                        <input value={app.uiFont} onChange={e => app.uiFont = e.target.value} onBlur={() => app.persistSettings()} style={styles.textInput} placeholder="System Sans" />
                      </SettingRow>
                      <SettingRow title="代码字体" desc="代码编辑器字体">
                        <input value={app.codeFont} onChange={e => app.codeFont = e.target.value} onBlur={() => app.persistSettings()} style={styles.textInput} placeholder="System Mono" />
                      </SettingRow>
                      <SettingRow title="终端字体" desc="终端面板字体">
                        <input value={app.terminalFont} onChange={e => app.terminalFont = e.target.value} onBlur={() => app.persistSettings()} style={styles.textInput} placeholder="JetBrains Mono" />
                      </SettingRow>
                    </div>
                  </div>

                  <div style={styles.section}>
                    <div style={styles.sectionTitle}>通知</div>
                    <div style={styles.settingsList}>
                      <SettingRow title="智能体通知" desc="智能体完成任务时发送通知">
                        <Toggle checked={app.notifAgent} onChange={() => { app.notifAgent = !app.notifAgent; app.persistSettings() }} />
                      </SettingRow>
                      <SettingRow title="权限通知" desc="需要用户授权时发送通知">
                        <Toggle checked={app.notifPermissions} onChange={() => { app.notifPermissions = !app.notifPermissions; app.persistSettings() }} />
                      </SettingRow>
                      <SettingRow title="错误通知" desc="发生错误时发送通知">
                        <Toggle checked={app.notifErrors} onChange={() => { app.notifErrors = !app.notifErrors; app.persistSettings() }} />
                      </SettingRow>
                    </div>
                  </div>

                  <div style={styles.section}>
                    <div style={styles.sectionTitle}>声音</div>
                    <div style={styles.settingsList}>
                      <SettingRow title="智能体提示音" desc="智能体完成时播放的提示音">
                        <select value={app.soundAgent} onChange={e => { app.soundAgent = e.target.value; app.persistSettings() }} style={styles.selectInput}>
                          {soundOptions.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
                        </select>
                      </SettingRow>
                      <SettingRow title="权限提示音" desc="需要授权时播放的提示音">
                        <select value={app.soundPermissions} onChange={e => { app.soundPermissions = e.target.value; app.persistSettings() }} style={styles.selectInput}>
                          {soundOptions.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
                        </select>
                      </SettingRow>
                      <SettingRow title="错误提示音" desc="发生错误时播放的提示音">
                        <select value={app.soundErrors} onChange={e => { app.soundErrors = e.target.value; app.persistSettings() }} style={styles.selectInput}>
                          {soundOptions.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
                        </select>
                      </SettingRow>
                    </div>
                  </div>
                </div>
              </>
            )}

            {activeTab === 'shortcuts' && (
              <>
                <div style={styles.tabHeader}>
                  <span>快捷键</span>
                  <button
                    style={{ ...styles.resetBtn, ...(!hasOverrides ? { opacity: 0.4, cursor: 'not-allowed' as const } : {}) }}
                    onClick={resetShortcuts}
                    disabled={!hasOverrides}
                  >重置全部</button>
                </div>
                <div style={styles.tabBody}>
                  <div style={{ position: 'relative' }}>
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} style={styles.searchIcon}>
                      <circle cx={11} cy={11} r={8} /><line x1={21} y1={21} x2={16.65} y2={16.65} />
                    </svg>
                    <input
                      value={shortcutSearch}
                      onChange={e => setShortcutSearch(e.target.value)}
                      style={styles.searchInput}
                      placeholder="搜索快捷键..."
                    />
                  </div>
                  {filteredShortcutGroups.map(group => (
                    <div key={group.name} style={styles.shortcutGroup}>
                      <div style={styles.groupLabel}>{group.name}</div>
                      <div style={styles.settingsList}>
                        {group.items.map(s => (
                          <div key={s.id} style={styles.shortcutRow}>
                            <div style={styles.rowCopy}>
                              <div style={styles.rowTitle}>{s.desc}</div>
                            </div>
                            <div style={styles.rowControl}>
                              <button
                                style={{
                                  ...styles.keybindBtn,
                                  ...(capturingId === s.id ? styles.keybindBtnCapturing : {}),
                                }}
                                onClick={() => startCapture(s.id)}
                              >
                                {capturingId === s.id ? '按下按键...' : formatKey(shortcutOverrides[s.id] || s.bind)}
                              </button>
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

function SettingRow({ title, desc, children }: { title: string; desc: string; children: React.ReactNode }) {
  return (
    <div style={rowStyles.row}>
      <div style={rowStyles.copy}>
        <div style={rowStyles.title}>{title}</div>
        <div style={rowStyles.desc}>{desc}</div>
      </div>
      <div style={rowStyles.control}>{children}</div>
    </div>
  )
}

const rowStyles = {
  row: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 16px', minHeight: 44, boxShadow: 'inset 0 -0.5px 0 var(--border)' } as React.CSSProperties,
  copy: { flex: 1, minWidth: 0, marginRight: 16 } as React.CSSProperties,
  title: { fontSize: 13, fontWeight: 500, color: 'var(--text-primary)' } as React.CSSProperties,
  desc: { fontSize: 11, color: 'var(--text-muted)', marginTop: 2 } as React.CSSProperties,
  control: { flexShrink: 0, display: 'flex', alignItems: 'center' } as React.CSSProperties,
}

const styles: Record<string, React.CSSProperties> = {
  overlay: { position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 },
  dialog: { background: 'var(--bg-secondary)', border: '1px solid var(--border)', borderRadius: 12, width: 780, height: 580, display: 'flex', flexDirection: 'column', overflow: 'hidden' },
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '14px 20px', borderBottom: '1px solid var(--border)', flexShrink: 0 },
  title: { margin: 0, fontSize: 15, fontWeight: 600, color: 'var(--text-primary)' },
  closeBtn: { background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer', fontSize: 20, padding: '2px 8px', borderRadius: 6, lineHeight: 1 },
  body: { display: 'flex', flex: 1, minHeight: 0 },
  sidebar: { width: 180, borderRight: '1px solid var(--border)', display: 'flex', flexDirection: 'column', padding: 8, flexShrink: 0 },
  sidebarSection: { marginBottom: 8 },
  sectionLabel: { fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5, padding: '8px 10px 4px' },
  sidebarTab: { display: 'flex', alignItems: 'center', gap: 8, width: '100%', padding: '7px 10px', borderRadius: 6, border: 'none', background: 'none', color: 'var(--text-secondary)', cursor: 'pointer', fontSize: 13, textAlign: 'left' },
  sidebarTabActive: { background: 'var(--bg-active)', color: 'var(--text-primary)', fontWeight: 500 },
  sidebarFooter: { marginTop: 'auto', padding: '12px 10px', display: 'flex', alignItems: 'center', gap: 6 },
  appName: { fontSize: 12, color: 'var(--text-secondary)', fontWeight: 500 },
  appVersion: { fontSize: 11, color: 'var(--text-muted)' },
  content: { flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0, overflow: 'hidden' },
  tabHeader: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 24px 0', fontSize: 18, fontWeight: 600, color: 'var(--text-primary)', flexShrink: 0 },
  tabBody: { flex: 1, overflowY: 'auto', padding: '16px 24px 24px', display: 'flex', flexDirection: 'column', gap: 24 },
  section: { display: 'flex', flexDirection: 'column', gap: 10 },
  sectionTitle: { fontSize: 13, fontWeight: 600, color: 'var(--text-secondary)', paddingLeft: 2 },
  settingsList: { borderRadius: 8, background: 'var(--bg-primary)', boxShadow: 'inset 0 0 0 0.5px var(--border)' },
  textInput: { width: 200, padding: '6px 10px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--bg-secondary)', color: 'var(--text-primary)', fontSize: 13, outline: 'none' },
  selectInput: { padding: '6px 10px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--bg-secondary)', color: 'var(--text-primary)', fontSize: 13, outline: 'none', minWidth: 140 },
  modeBtn: { display: 'flex', alignItems: 'center', gap: 5, padding: '5px 10px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--bg-primary)', color: 'var(--text-secondary)', cursor: 'pointer', fontSize: 12 },
  modeBtnActive: { background: 'var(--accent)', borderColor: 'var(--accent)', color: 'white' },
  themeGrid: { display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(90px, 1fr))', gap: 6 },
  themeCard: { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 5, padding: '8px 4px', borderRadius: 8, border: '2px solid var(--border)', background: 'var(--bg-primary)', cursor: 'pointer' },
  themeCardActive: { borderColor: 'var(--accent)' },
  themePreview: { display: 'flex', gap: 3 },
  previewDot: { width: 10, height: 10, borderRadius: '50%', border: '1px solid rgba(128,128,128,0.2)' },
  themeName: { fontSize: 10, color: 'var(--text-secondary)', textAlign: 'center', lineHeight: 1.2, wordBreak: 'break-word' },
  searchIcon: { position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', width: 14, height: 14, color: 'var(--text-muted)' },
  searchInput: { width: '100%', padding: '8px 12px 8px 32px', borderRadius: 8, border: '1px solid var(--border)', background: 'var(--bg-primary)', color: 'var(--text-primary)', fontSize: 13, outline: 'none', boxSizing: 'border-box' },
  shortcutGroup: { display: 'flex', flexDirection: 'column', gap: 8 },
  groupLabel: { fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 0.5 },
  shortcutRow: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 16px', minHeight: 44, boxShadow: 'inset 0 -0.5px 0 var(--border)' },
  rowCopy: { flex: 1, minWidth: 0, marginRight: 16 },
  rowTitle: { fontSize: 13, fontWeight: 500, color: 'var(--text-primary)' },
  rowControl: { flexShrink: 0, display: 'flex', alignItems: 'center' },
  keybindBtn: { padding: '4px 10px', borderRadius: 5, border: '1px solid var(--border)', background: 'var(--bg-primary)', color: 'var(--text-secondary)', fontSize: 11, fontFamily: "'JetBrains Mono', monospace", cursor: 'pointer', whiteSpace: 'nowrap' },
  keybindBtnCapturing: { borderColor: 'var(--accent)', color: 'var(--accent)' },
  resetBtn: { padding: '4px 10px', borderRadius: 6, border: '1px solid var(--border)', background: 'var(--bg-primary)', color: 'var(--text-secondary)', fontSize: 12, cursor: 'pointer' },
}
