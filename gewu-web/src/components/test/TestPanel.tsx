import { useState, useEffect } from 'react'

interface TestCase {
  name: string
  file: string
  description?: string
}

interface TestStep {
  stepIndex: number
  name: string
  status: 'pending' | 'running' | 'passed' | 'failed'
  error?: string
}

interface TestRun {
  id: string
  status: 'running' | 'passed' | 'failed'
  startedAt: string
}

export default function TestPanel() {
  const [activeTab, setActiveTab] = useState<'cases' | 'running' | 'history'>('cases')
  const [testCases, setTestCases] = useState<TestCase[]>([])
  const [isRunning, setIsRunning] = useState(false)
  const [currentRun, setCurrentRun] = useState<TestRun | null>(null)
  const [currentSteps, setCurrentSteps] = useState<TestStep[]>([])
  const [showRunDialog, setShowRunDialog] = useState(false)
  const [selectedCase, setSelectedCase] = useState<TestCase | null>(null)
  const [yamlContent, setYamlContent] = useState('')

  useEffect(() => {
    loadCases()
  }, [])

  function loadCases() {
    // TODO: call testApi.getCases
    setTestCases([])
  }

  async function runTestCase(tc: TestCase) {
    setActiveTab('running')
    setIsRunning(true)
    setCurrentRun({ id: Date.now().toString(), status: 'running', startedAt: new Date().toISOString() })
    // TODO: call testApi.run
    setTimeout(() => {
      setIsRunning(false)
      setCurrentRun(prev => prev ? { ...prev, status: 'passed' } : null)
    }, 2000)
  }

  async function handleRun() {
    if (!yamlContent) return
    setShowRunDialog(false)
    setActiveTab('running')
    setIsRunning(true)
    setCurrentRun({ id: Date.now().toString(), status: 'running', startedAt: new Date().toISOString() })
    // TODO: call testApi.runWithYaml
    setTimeout(() => {
      setIsRunning(false)
      setCurrentRun(prev => prev ? { ...prev, status: 'passed' } : null)
    }, 2000)
    setYamlContent('')
    setSelectedCase(null)
  }

  return (
    <div style={styles.panel}>
      <div style={styles.header}>
        <h3 style={styles.title}>自动化测试</h3>
        <div style={styles.actions}>
          <button style={styles.btnSm} onClick={loadCases} title="刷新用例">刷新</button>
          <button
            style={{ ...styles.btnSm, ...styles.btnPrimary }}
            onClick={() => setShowRunDialog(true)}
            disabled={isRunning}
          >运行</button>
        </div>
      </div>

      <div style={styles.tabs}>
        <button style={{ ...styles.tabBtn, ...(activeTab === 'cases' ? styles.tabBtnActive : {}) }} onClick={() => setActiveTab('cases')}>用例</button>
        <button style={{ ...styles.tabBtn, ...(activeTab === 'running' ? styles.tabBtnActive : {}) }} onClick={() => setActiveTab('running')}>
          运行中
          {isRunning && <span style={styles.badge}>●</span>}
        </button>
        <button style={{ ...styles.tabBtn, ...(activeTab === 'history' ? styles.tabBtnActive : {}) }} onClick={() => setActiveTab('history')}>历史</button>
      </div>

      <div style={styles.content}>
        {activeTab === 'cases' && (
          <div>
            {testCases.length === 0 ? (
              <div style={styles.emptyState}>
                <p>暂无测试用例</p>
                <p style={styles.hint}>将 .yaml 文件放入 test-cases/ 目录</p>
              </div>
            ) : (
              testCases.map(tc => (
                <div key={tc.file} style={styles.caseItem} onClick={() => runTestCase(tc)}>
                  <div style={styles.caseName}>{tc.name}</div>
                  <div style={styles.caseDesc}>{tc.description || tc.file}</div>
                </div>
              ))
            )}
          </div>
        )}

        {activeTab === 'running' && (
          <div>
            {!currentRun ? (
              <div style={styles.emptyState}>暂无运行中的测试</div>
            ) : (
              <div>
                <div style={styles.runStatus}>
                  状态: {currentRun.status === 'running' ? '运行中...' : currentRun.status === 'passed' ? '通过' : '失败'}
                </div>
                {currentSteps.length > 0 && (
                  <div style={styles.stepList}>
                    {currentSteps.map(step => (
                      <div key={step.stepIndex} style={styles.stepItem}>
                        <span style={{ ...styles.stepStatus, color: step.status === 'passed' ? '#10b981' : step.status === 'failed' ? '#ef4444' : '#f59e0b' }}>
                          {step.status === 'passed' ? '✓' : step.status === 'failed' ? '✗' : '○'}
                        </span>
                        <span>{step.name}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {activeTab === 'history' && (
          <div style={styles.emptyState}>暂无历史记录</div>
        )}
      </div>

      {showRunDialog && (
        <div style={styles.modalOverlay} onClick={() => setShowRunDialog(false)}>
          <div style={styles.modal} onClick={e => e.stopPropagation()}>
            <div style={styles.modalHeader}>
              <h3 style={{ margin: 0, fontSize: 14 }}>运行测试用例</h3>
              <button style={styles.btnClose} onClick={() => setShowRunDialog(false)}>&times;</button>
            </div>
            <div style={styles.modalBody}>
              <div style={styles.formGroup}>
                <label style={styles.label}>选择用例</label>
                <select
                  value={selectedCase?.file || ''}
                  onChange={e => {
                    const tc = testCases.find(c => c.file === e.target.value)
                    setSelectedCase(tc || null)
                    if (tc) setYamlContent(`name: ${tc.name}\ndescription: ${tc.description || ''}`)
                  }}
                  style={styles.select}
                >
                  <option value="">-- 选择已有用例 --</option>
                  {testCases.map(tc => <option key={tc.file} value={tc.file}>{tc.name}</option>)}
                </select>
              </div>
              {selectedCase && (
                <div style={styles.formGroup}>
                  <label style={styles.label}>用例内容</label>
                  <textarea
                    value={yamlContent}
                    onChange={e => setYamlContent(e.target.value)}
                    style={styles.textarea}
                    rows={10}
                    placeholder="粘贴 YAML 测试用例..."
                  />
                </div>
              )}
            </div>
            <div style={styles.modalFooter}>
              <button style={styles.btn} onClick={() => setShowRunDialog(false)}>取消</button>
              <button
                style={{ ...styles.btn, ...styles.btnPrimary }}
                onClick={handleRun}
                disabled={!yamlContent}
              >运行</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  panel: { display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' },
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px 16px', borderBottom: '1px solid var(--border)' },
  title: { fontSize: 14, fontWeight: 600, color: 'var(--text-primary)', margin: 0 },
  actions: { display: 'flex', gap: 6 },
  tabs: { display: 'flex', borderBottom: '1px solid var(--border)', padding: '0 8px' },
  tabBtn: { background: 'none', border: 'none', padding: '8px 12px', fontSize: 12, color: 'var(--text-secondary)', cursor: 'pointer', borderBottom: '2px solid transparent' },
  tabBtnActive: { color: 'var(--accent)', borderBottomColor: 'var(--accent)' },
  badge: { color: '#f59e0b', fontSize: 10, marginLeft: 4 },
  content: { flex: 1, overflowY: 'auto', padding: 8 },
  emptyState: { padding: 24, textAlign: 'center', color: 'var(--text-secondary)', fontSize: 13 },
  hint: { fontSize: 11, marginTop: 8, opacity: 0.6 },
  caseItem: { padding: '10px 12px', borderRadius: 6, cursor: 'pointer', border: '1px solid var(--border)', marginBottom: 6 },
  caseName: { fontSize: 13, fontWeight: 600, color: 'var(--text-primary)' },
  caseDesc: { fontSize: 11, color: 'var(--text-secondary)', marginTop: 2 },
  runStatus: { padding: 12, fontSize: 13, color: 'var(--text-primary)' },
  stepList: { marginTop: 8 },
  stepItem: { display: 'flex', alignItems: 'center', gap: 8, padding: '6px 12px', fontSize: 13, color: 'var(--text-primary)' },
  stepStatus: { fontWeight: 600 },
  modalOverlay: { position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 },
  modal: { background: 'var(--bg-primary)', borderRadius: 8, width: 520, maxHeight: '80vh', display: 'flex', flexDirection: 'column', border: '1px solid var(--border)' },
  modalHeader: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px 16px', borderBottom: '1px solid var(--border)' },
  modalBody: { padding: 16, flex: 1, overflowY: 'auto' },
  modalFooter: { padding: '12px 16px', borderTop: '1px solid var(--border)', display: 'flex', justifyContent: 'flex-end', gap: 8 },
  btnClose: { background: 'none', border: 'none', fontSize: 18, cursor: 'pointer', color: 'var(--text-secondary)' },
  formGroup: { marginBottom: 12 },
  label: { display: 'block', fontSize: 12, color: 'var(--text-secondary)', marginBottom: 4 },
  select: { width: '100%', padding: '6px 10px', fontSize: 13, borderRadius: 4, border: '1px solid var(--border)', background: 'var(--bg-secondary)', color: 'var(--text-primary)' },
  textarea: { width: '100%', padding: '6px 10px', fontSize: 12, borderRadius: 4, border: '1px solid var(--border)', background: 'var(--bg-secondary)', color: 'var(--text-primary)', fontFamily: "'JetBrains Mono', monospace", resize: 'vertical' },
  btn: { padding: '6px 12px', borderRadius: 4, fontSize: 12, cursor: 'pointer', border: '1px solid var(--border)', background: 'var(--bg-secondary)', color: 'var(--text-primary)' },
  btnSm: { padding: '4px 8px', fontSize: 11, borderRadius: 4, border: '1px solid var(--border)', background: 'var(--bg-secondary)', color: 'var(--text-primary)', cursor: 'pointer' },
  btnPrimary: { background: 'var(--accent)', color: 'white', border: '1px solid var(--accent)' },
}
