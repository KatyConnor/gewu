import React, { useState, useCallback, useRef, useEffect } from 'react'
import { Select, Input, Button } from 'antd'
import { useApp } from '../../stores/appStore'

interface ReviewForm {
  mode: string
  target: string
  provider: string
}

interface ReviewTask {
  task_id?: string
  status: 'queued' | 'running' | 'completed' | 'failed'
  issues?: { file_path?: string; severity?: string; message?: string; [key: string]: unknown }[]
  error?: string
  elapsed_seconds?: number
}

export default function ReviewSidePanel() {
  const app = useApp()
  const [form, setForm] = useState<ReviewForm>({ mode: 'local', target: '.', provider: '' })
  const [currentTask, setCurrentTask] = useState<ReviewTask | null>(null)
  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const targetPlaceholder = app.currentProject
    ? app.currentProject.path
    : form.mode === 'github' ? 'owner/repo#42' : form.mode === 'git_diff' ? '仓库路径 (默认: .)' : '目录路径'

  const canStart = !!form.target?.trim() && !!form.mode && !!app.currentProject
  const isRunning = app.reviewRunning

  const groupedIssues = React.useMemo(() => {
    if (!currentTask?.issues?.length) return {}
    const groups: Record<string, typeof currentTask.issues> = {}
    for (const issue of currentTask.issues) {
      const fp = issue.file_path || '未知文件'
      if (!groups[fp]) groups[fp] = []
      groups[fp].push(issue)
    }
    return groups
  }, [currentTask])

  useEffect(() => {
    if (app.currentProject) {
      setForm(f => ({ ...f, target: app.currentProject!.path }))
    }
  }, [app.currentProject])

  const statusLabel = (status: string) => {
    const map: Record<string, string> = { queued: '排队中', running: '运行中', completed: '完成', failed: '失败' }
    return map[status] || status
  }

  const formatTime = (seconds?: number) => {
    if (!seconds) return ''
    if (seconds < 60) return `${seconds.toFixed(1)}s`
    const m = Math.floor(seconds / 60)
    const s = (seconds % 60).toFixed(0)
    return `${m}m ${s}s`
  }

  const pollForResult = useCallback((taskId: string) => {
    pollTimerRef.current = setInterval(async () => {
      try {
        const data = await fetch(`/api/review/${taskId}`).then(r => r.json())
        setCurrentTask(data)
        if (data.status === 'completed' || data.status === 'failed') {
          if (pollTimerRef.current) { clearInterval(pollTimerRef.current); pollTimerRef.current = null }
          app.set('reviewRunning', false)
        }
      } catch (e) {
        console.error('Poll error:', e)
      }
    }, 2000)
  }, [app])

  const startReview = useCallback(async () => {
    if (!canStart || isRunning) return
    app.set('reviewRunning', true)
    setCurrentTask(null)
    try {
      const data = await fetch('/api/review', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mode: form.mode, target: form.target.trim(), provider: form.provider }),
      }).then(r => r.json())
      setCurrentTask({ task_id: data.task_id, status: data.status })
      pollForResult(data.task_id)
    } catch (e) {
      setCurrentTask({ status: 'failed', error: (e as Error).message })
      app.set('reviewRunning', false)
    }
  }, [canStart, isRunning, form, app, pollForResult])

  useEffect(() => {
    return () => { if (pollTimerRef.current) clearInterval(pollTimerRef.current) }
  }, [])

  const statusColorMap: Record<string, { bg: string; color: string }> = {
    queued: { bg: '#fef3c7', color: '#92400e' },
    running: { bg: '#dbeafe', color: '#1e40af' },
    completed: { bg: '#d1fae5', color: '#065f46' },
    failed: { bg: '#fee2e2', color: '#991b1b' },
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div style={{
        display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        padding: '12px 16px', borderBottom: '1px solid var(--border)',
      }}>
        <h3 style={{ margin: 0, fontSize: 14, fontWeight: 600, color: 'var(--text-primary)' }}>代码审查</h3>
        <button
          onClick={() => app.closeAllPanels()}
          style={{ background: 'none', border: 'none', color: 'var(--text-secondary)', cursor: 'pointer', fontSize: 16, padding: '4px 8px', borderRadius: 4 }}
        >✕</button>
      </div>

      <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)', display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>项目:</span>
        {app.currentProject ? (
          <span style={{ fontSize: 13, fontWeight: 500, color: 'var(--accent, #6366f1)' }}>{app.currentProject.name}</span>
        ) : (
          <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>请先打开项目</span>
        )}
      </div>

      <div style={{ padding: 16, borderBottom: '1px solid var(--border)', display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label style={{ fontSize: 12, fontWeight: 500, color: 'var(--text-secondary)' }}>模式</label>
          <Select
            value={form.mode}
            onChange={(v) => setForm(f => ({ ...f, mode: v }))}
            disabled={!app.currentProject}
            options={[
              { value: 'local', label: '本地目录' },
              { value: 'git_diff', label: 'Git Diff' },
              { value: 'github', label: 'GitHub PR' },
            ]}
            style={{ width: '100%' }}
          />
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label style={{ fontSize: 12, fontWeight: 500, color: 'var(--text-secondary)' }}>目标</label>
          <Input
            value={form.target}
            onChange={(e) => setForm(f => ({ ...f, target: e.target.value }))}
            placeholder={targetPlaceholder}
            disabled={!app.currentProject}
          />
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label style={{ fontSize: 12, fontWeight: 500, color: 'var(--text-secondary)' }}>LLM</label>
          <Select
            value={form.provider || undefined}
            onChange={(v) => setForm(f => ({ ...f, provider: v || '' }))}
            disabled={!app.currentProject}
            allowClear
            placeholder="默认"
            options={[
              { value: 'openai', label: 'OpenAI' },
              { value: 'deepseek', label: 'DeepSeek' },
              { value: 'zhipu', label: '智谱 GLM' },
              { value: 'qwen', label: '通义千问' },
              { value: 'mimo', label: 'Mimo' },
            ]}
            style={{ width: '100%' }}
          />
        </div>
        <Button
          type="primary"
          onClick={startReview}
          disabled={!canStart || isRunning}
          loading={isRunning}
          style={{ background: 'var(--accent, #6366f1)', borderColor: 'var(--accent, #6366f1)' }}
        >
          {isRunning ? '审查中...' : '开始审查'}
        </Button>
      </div>

      {currentTask && (
        <div style={{ flex: 1, overflowY: 'auto', padding: 16 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 16 }}>
            <span style={{
              padding: '2px 8px', borderRadius: 12, fontSize: 12, fontWeight: 500,
              ...(() => {
                const c = statusColorMap[currentTask.status] || { bg: '#f3f4f6', color: '#374151' }
                return { background: c.bg, color: c.color }
              })(),
            }}>{statusLabel(currentTask.status)}</span>
            {currentTask.elapsed_seconds && (
              <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>
                {formatTime(currentTask.elapsed_seconds)}
              </span>
            )}
          </div>

          {currentTask.status === 'completed' && currentTask.issues?.length ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {Object.entries(groupedIssues).map(([filePath, issues]) => (
                <div key={filePath} style={{
                  background: 'var(--bg-primary)', borderRadius: 8, padding: 12,
                  border: '1px solid var(--border)',
                }}>
                  <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 8, fontFamily: 'monospace' }}>
                    {filePath}
                  </div>
                  {issues.map((issue, idx) => (
                    <div key={idx} style={{ fontSize: 12, color: 'var(--text-secondary)', padding: '4px 0', borderTop: idx > 0 ? '1px solid var(--border)' : undefined }}>
                      <span style={{
                        padding: '1px 6px', borderRadius: 4, fontSize: 10, fontWeight: 600, marginRight: 6,
                        background: issue.severity === 'error' ? '#ef444422' : issue.severity === 'warning' ? '#f59e0b22' : '#3b82f622',
                        color: issue.severity === 'error' ? '#ef4444' : issue.severity === 'warning' ? '#f59e0b' : '#3b82f6',
                      }}>{issue.severity || 'info'}</span>
                      {issue.message}
                    </div>
                  ))}
                </div>
              ))}
            </div>
          ) : currentTask.status === 'completed' ? (
            <div style={{ textAlign: 'center', padding: 32, color: 'var(--text-secondary)', fontSize: 14 }}>
              ✨ 未发现问题
            </div>
          ) : currentTask.status === 'failed' ? (
            <div style={{ padding: 12, background: '#fee2e2', borderRadius: 8, color: '#991b1b', fontSize: 13 }}>
              错误: {currentTask.error}
            </div>
          ) : null}
        </div>
      )}
    </div>
  )
}
