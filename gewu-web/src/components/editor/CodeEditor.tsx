import { useState, useEffect, useRef, useCallback } from 'react'
import { useApp } from '../../stores/appStore'
import EditorHeader from './EditorHeader'
import EditorFooter from './EditorFooter'
import SaveDialog from './SaveDialog'

interface Tab {
  id: string
  path: string
  name: string
  modified: boolean
  language: string
}

function getLanguageByPath(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase() || ''
  const map: Record<string, string> = {
    js: 'javascript', mjs: 'javascript', jsx: 'javascript',
    ts: 'typescript', tsx: 'typescript',
    vue: 'vue', html: 'html', css: 'css', scss: 'scss', less: 'less',
    json: 'json', md: 'markdown', py: 'python', java: 'java',
    go: 'go', rs: 'rust', sql: 'sql', sh: 'shell', yaml: 'yaml', yml: 'yaml',
  }
  return map[ext] || 'plaintext'
}

interface Props {
  readOnly?: boolean
}

export default function CodeEditor({ readOnly = false }: Props) {
  const app = useApp()
  const [tabs, setTabs] = useState<Tab[]>([])
  const [activeTabId, setActiveTabId] = useState<string | null>(null)
  const [content, setContent] = useState('')
  const [cursorLine, setCursorLine] = useState(1)
  const [cursorColumn, setCursorColumn] = useState(1)
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  const [pendingTab, setPendingTab] = useState<Tab | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const lineNumbersRef = useRef<HTMLDivElement>(null)
  const tabContentCache = useRef<Record<string, string>>({})
  const saveResolveRef = useRef<((action: string) => void) | null>(null)

  const activeTab = tabs.find(t => t.id === activeTabId)
  const lineCount = content ? content.split('\n').length : 1

  useEffect(() => {
    function handleBeforeUnload(e: BeforeUnloadEvent) {
      if (tabs.some(t => t.modified)) {
        e.preventDefault()
        e.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => window.removeEventListener('beforeunload', handleBeforeUnload)
  }, [tabs])

  function openFile(path: string) {
    const existing = tabs.find(t => t.path === path)
    if (existing) {
      setActiveTabId(existing.id)
      return
    }
    const tab: Tab = {
      id: Date.now().toString(),
      path,
      name: path.split('/').pop() || path,
      language: getLanguageByPath(path),
      modified: false,
    }
    setTabs(prev => [...prev, tab])
    setActiveTabId(tab.id)
  }

  async function loadFileContent(path: string) {
    try {
      // TODO: call fsApi.read
      const content = ''
      setContent(content)
      tabContentCache.current[path] = content
    } catch {
      setContent('')
    }
  }

  function selectTab(tabId: string) {
    setActiveTabId(tabId)
    const tab = tabs.find(t => t.id === tabId)
    if (tab) loadFileContent(tab.path)
  }

  function doCloseTab(tabId: string) {
    setTabs(prev => {
      const index = prev.findIndex(t => t.id === tabId)
      if (index === -1) return prev
      const newTabs = prev.filter(t => t.id !== tabId)
      if (activeTabId === tabId) {
        const newIndex = Math.min(index, newTabs.length - 1)
        const newActiveId = newTabs[newIndex]?.id || null
        setActiveTabId(newActiveId)
        if (newActiveId) {
          const tab = newTabs.find(t => t.id === newActiveId)
          if (tab) loadFileContent(tab.path)
        }
      }
      return newTabs
    })
  }

  function confirmSave(tab: Tab): Promise<string> {
    return new Promise(resolve => {
      setPendingTab(tab)
      setShowSaveDialog(true)
      saveResolveRef.current = resolve
    })
  }

  function handleSaveDialogAction(action: string) {
    setShowSaveDialog(false)
    saveResolveRef.current?.(action)
    saveResolveRef.current = null
  }

  async function closeTab(tabId: string) {
    const tab = tabs.find(t => t.id === tabId)
    if (!tab) return
    if (tab.modified) {
      const action = await confirmSave(tab)
      if (action === 'cancel') return
      if (action === 'save') {
        // TODO: save file
        setTabs(prev => prev.map(t => t.id === tabId ? { ...t, modified: false } : t))
      }
    }
    doCloseTab(tabId)
  }

  function closeAllTabs() {
    setTabs([])
    setActiveTabId(null)
    setContent('')
  }

  function closeOtherTabs(keepTabId: string) {
    setTabs(prev => prev.filter(t => t.id === keepTabId))
    if (!tabs.find(t => t.id === activeTabId)) {
      setActiveTabId(keepTabId)
      selectTab(keepTabId)
    }
  }

  function handleInput(e: React.ChangeEvent<HTMLTextAreaElement>) {
    const val = e.target.value
    setContent(val)
    updateCursorPosition()
    if (activeTab) {
      tabContentCache.current[activeTab.path] = val
      setTabs(prev => prev.map(t => t.id === activeTab.id ? { ...t, modified: true } : t))
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.ctrlKey || e.metaKey) {
      if (e.key === 's') { e.preventDefault(); saveFile() }
      if (e.key === 'w') { e.preventDefault(); if (activeTab) closeTab(activeTab.id) }
    }
    // Tab key for indentation
    if (e.key === 'Tab') {
      e.preventDefault()
      const textarea = textareaRef.current
      if (!textarea) return
      const start = textarea.selectionStart
      const end = textarea.selectionEnd
      const newContent = content.substring(0, start) + '  ' + content.substring(end)
      setContent(newContent)
      if (activeTab) {
        tabContentCache.current[activeTab.path] = newContent
        setTabs(prev => prev.map(t => t.id === activeTab.id ? { ...t, modified: true } : t))
      }
      setTimeout(() => {
        textarea.selectionStart = textarea.selectionEnd = start + 2
      }, 0)
    }
  }

  function handleScroll(e: React.UIEvent<HTMLDivElement>) {
    if (lineNumbersRef.current) {
      lineNumbersRef.current.scrollTop = (e.target as HTMLDivElement).scrollTop
    }
  }

  function updateCursorPosition() {
    const textarea = textareaRef.current
    if (!textarea) return
    const text = textarea.value
    const pos = textarea.selectionStart
    const lines = text.substring(0, pos).split('\n')
    setCursorLine(lines.length)
    setCursorColumn(lines[lines.length - 1].length + 1)
  }

  async function saveFile() {
    if (!activeTab) return
    // TODO: call fsApi.write
    setTabs(prev => prev.map(t => t.id === activeTab.id ? { ...t, modified: false } : t))
    app.saveFile(activeTab.path)
  }

  useEffect(() => {
    if (app.activeFile) {
      openFile(app.activeFile)
    }
  }, [app.activeFile])

  return (
    <div style={styles.editor}>
      <EditorHeader
        tabs={tabs}
        activeTabId={activeTabId}
        onSelect={selectTab}
        onClose={closeTab}
        onCloseAll={closeAllTabs}
        onCloseOthers={closeOtherTabs}
      />

      {activeTab ? (
        <>
          <div style={styles.body}>
            <div style={styles.lineNumbers} ref={lineNumbersRef}>
              {Array.from({ length: lineCount }, (_, i) => (
                <div
                  key={i + 1}
                  style={{
                    ...styles.lineNumber,
                    ...(i + 1 === cursorLine ? styles.lineNumberActive : {}),
                  }}
                >
                  {i + 1}
                </div>
              ))}
            </div>
            <div style={styles.codeArea} onScroll={handleScroll}>
              <textarea
                ref={textareaRef}
                value={content}
                onChange={handleInput}
                onKeyDown={handleKeyDown}
                onFocus={updateCursorPosition}
                style={styles.codeInput}
                spellCheck={false}
                readOnly={readOnly}
                autoComplete="off"
                autoCorrect="off"
                autoCapitalize="off"
              />
            </div>
          </div>
          <EditorFooter
            cursorLine={cursorLine}
            cursorColumn={cursorColumn}
            language={activeTab.language}
            isModified={activeTab.modified}
            encoding="UTF-8"
          />
        </>
      ) : (
        <div style={styles.empty}>
          <div style={styles.emptyContent}>
            <svg width={64} height={64} viewBox="0 0 64 64" fill="none">
              <path d="M12 8C12 6.89543 12.8954 6 14 6H30L34 10H50C51.1046 10 52 10.8954 52 12V52C52 53.1046 51.1046 54 50 54H14C12.8954 54 12 53.1046 12 52V8Z" fill="#424242" />
              <path d="M12 12H52V52C52 53.1046 51.1046 54 50 54H14C12.8954 54 12 53.1046 12 52V12Z" fill="#616161" />
              <path d="M24 28H40M24 36H36" stroke="#9E9E9E" strokeWidth={2} strokeLinecap="round" />
            </svg>
            <h3 style={{ margin: '16px 0 8px', fontSize: 16, fontWeight: 500, color: 'var(--text-muted, #666)' }}>没有打开的文件</h3>
            <p style={{ fontSize: 13, color: 'var(--text-muted, #666)' }}>在文件树中双击文件即可打开</p>
          </div>
        </div>
      )}

      <SaveDialog
        visible={showSaveDialog}
        fileName={pendingTab?.name || ''}
        onAction={handleSaveDialogAction}
      />
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  editor: { display: 'flex', flexDirection: 'column', height: '100%', background: 'var(--editor-bg, #1e1e1e)', color: 'var(--code-text, #d4d4d4)', fontFamily: "var(--editor-font-family, 'JetBrains Mono', monospace)", fontSize: 14, lineHeight: 1.5 },
  body: { flex: 1, display: 'flex', overflow: 'hidden', position: 'relative' },
  lineNumbers: { width: 60, background: 'var(--line-number-bg, #1e1e1e)', color: 'var(--line-number-color, #858585)', fontSize: 12, textAlign: 'right', padding: '8px 8px 8px 0', userSelect: 'none', overflow: 'hidden' },
  lineNumber: { height: 21, lineHeight: '21px' },
  lineNumberActive: { color: 'var(--line-number-active-color, #c6c6c6)' },
  codeArea: { flex: 1, position: 'relative', overflow: 'auto' },
  codeInput: { width: '100%', height: '100%', padding: 8, background: 'transparent', color: 'transparent', caretColor: '#aeafad', border: 'none', outline: 'none', resize: 'none', overflow: 'hidden', fontFamily: 'inherit', fontSize: 'inherit', lineHeight: 'inherit', whiteSpace: 'pre-wrap', wordWrap: 'break-word', tabSize: 2 },
  empty: { flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' },
  emptyContent: { textAlign: 'center', color: 'var(--text-muted, #666)' },
}
