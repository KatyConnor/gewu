import React, { useState, useRef, useCallback, useEffect, useMemo } from 'react'
import { Input, Button, Tooltip, Select } from 'antd'
import { PaperClipOutlined, SendOutlined, PauseOutlined } from '@ant-design/icons'
import { useApp } from '../../stores/appStore'
import { useChat } from '../../stores/chatStore'
import { fsApi, permissionApi } from '../../api/chat'

const THINKING_OPTIONS = [
  { value: 'none', label: '关闭', desc: '关闭深度思考' },
  { value: 'low', label: '浅度', desc: '轻度推理' },
  { value: 'medium', label: '标准', desc: '标准推理' },
  { value: 'high', label: '深度', desc: '深度推理，更全面的分析' },
]

const AGENT_NAME_MAP: Record<string, string> = {
  build: '构建',
  plan: '规划',
  general: '通用',
  explore: '探索',
  compaction: '压缩',
  title: '标题',
  summary: '摘要',
}

interface Attachment {
  name: string
  size: number
  type: string
  file: File
  preview?: string
}

export default function PromptInput() {
  const [input, setInput] = useState('')
  const [selectedModel, setSelectedModel] = useState('')
  const [selectedAgent, setSelectedAgent] = useState('build')
  const [attachments, setAttachments] = useState<Attachment[]>([])
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const isStreaming = useChat((s) => s.isStreaming)
  const thinkingIntensity = useChat((s) => s.thinkingIntensity)
  const setThinkingIntensity = useChat((s) => s.setThinkingIntensity)
  const sendMessage = useChat((s) => s.sendMessage)
  const permissionRequest = useChat((s) => s.permissionRequest)
  const streamingToolCalls = useChat((s) => s.streamingToolCalls)
  const currentSessionId = useApp((s) => s.currentSessionId)
  const currentSession = useApp((s) => s.currentSession)
  const agents = useApp((s) => s.agents)
  const providers = useApp((s) => s.providers)

  const visibleAgents = useMemo(
    () => agents.filter((a) => !['compaction', 'title', 'summary'].includes(a.name)),
    [agents]
  )

  const groupedModels = useMemo(() => {
    const popularOrder = ['opencode', 'opencode-go', 'anthropic', 'github', 'openai', 'google', 'openrouter', 'deepseek', 'zhipu', 'agnes']
    const result: Array<{ provider: string; providerID: string; models: Array<{ id: string; name?: string }> }> = []
    for (const p of providers || []) {
      let models = p.models
      if (models && !Array.isArray(models)) {
        models = Object.values(models) as Array<{ id: string; name?: string; status?: string; cost?: { input?: number } }>
      }
      if (!Array.isArray(models) || !models.length) continue
      const filtered = models.filter(
        (m) => m.status !== 'deprecated' && (p.id === 'opencode' ? m.cost?.input === 0 : p.source === 'config')
      )
      if (filtered.length) {
        filtered.sort((a, b) => (a.name || a.id || '').localeCompare(b.name || b.id || ''))
        result.push({ provider: p.name || p.id, providerID: p.id, models: filtered })
      }
    }
    result.sort((a, b) => {
      const ai = popularOrder.indexOf(a.providerID)
      const bi = popularOrder.indexOf(b.providerID)
      const aPop = ai >= 0, bPop = bi >= 0
      if (aPop && !bPop) return -1
      if (!aPop && bPop) return 1
      if (aPop && bPop) return ai - bi
      return (a.provider || '').localeCompare(b.provider || '')
    })
    return result
  }, [providers])

  const completedSteps = useMemo(
    () => streamingToolCalls.filter((t) => t.status === 'completed' || t.status === 'error').length,
    [streamingToolCalls]
  )

  const allStepsCompleted = useMemo(
    () =>
      streamingToolCalls.length > 0 &&
      streamingToolCalls.every((t) => t.status === 'completed' || t.status === 'error'),
    [streamingToolCalls]
  )

  const autoResize = useCallback(() => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    const maxHeight = 150
    el.style.height = Math.min(el.scrollHeight, maxHeight) + 'px'
    el.style.overflowY = el.scrollHeight > maxHeight ? 'auto' : 'hidden'
  }, [])

  useEffect(() => {
    autoResize()
  }, [input, autoResize])

  const fileToBase64 = useCallback((file: File): Promise<string> => {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => {
        const result = reader.result as string
        resolve(result.split(',')[1])
      }
      reader.onerror = reject
      reader.readAsDataURL(file)
    })
  }, [])

  const sendMessage_ = useCallback(async () => {
    if (isStreaming) return
    const text = input.trim()
    if (!text && !attachments.length) return

    const images: Array<{ data: string; mimeType: string; name: string }> = []
    for (const att of attachments) {
      if (att.type.startsWith('image/') && att.file) {
        try {
          const base64Data = await fileToBase64(att.file)
          images.push({ data: base64Data, mimeType: att.type, name: att.name })
        } catch (e) {
          console.warn('图片转换失败:', e)
        }
      }
    }

    setInput('')
    setAttachments([])
    requestAnimationFrame(autoResize)

    if (!currentSessionId) {
      await useApp.getState().createSession()
    }

    const sessionId = currentSessionId || useApp.getState().currentSessionId
    if (!sessionId) return

    let providerID: string | undefined
    let modelID: string | undefined
    if (selectedModel) {
      const parts = selectedModel.split('/')
      providerID = parts[0]
      modelID = parts[1]
    }

    useChat.getState().setSelectedAgent(selectedAgent || 'build')
    await sendMessage(sessionId, text, providerID, modelID, thinkingIntensity, images)
  }, [input, attachments, isStreaming, currentSessionId, selectedModel, selectedAgent, thinkingIntensity, sendMessage, fileToBase64, autoResize])

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        sendMessage_()
      }
    },
    [sendMessage_]
  )

  const handleFilesSelected = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const files = e.target.files
      if (!files) return
      const newAttachments: Attachment[] = []
      for (const file of Array.from(files)) {
        const attachment: Attachment = {
          name: file.name,
          size: file.size,
          type: file.type,
          file,
        }
        if (file.type.startsWith('image/')) {
          const reader = new FileReader()
          reader.onload = (event) => {
            attachment.preview = event.target?.result as string
          }
          reader.readAsDataURL(file)
        }
        newAttachments.push(attachment)
      }
      setAttachments((prev) => [...prev, ...newAttachments])
      e.target.value = ''
    },
    []
  )

  const removeAttachment = useCallback((index: number) => {
    setAttachments((prev) => prev.filter((_, i) => i !== index))
  }, [])

  const respondPermission = useCallback(
    async (response: string) => {
      if (!permissionRequest) return
      try {
        await permissionApi.respond(
          permissionRequest.sessionID,
          permissionRequest.id,
          response,
          response === 'allow_always'
        )
        useChat.getState().clearPermissionRequest()
      } catch (e: unknown) {
        const err = e instanceof Error ? e : new Error(String(e))
        console.error('权限响应失败:', err.message)
      }
    },
    [permissionRequest]
  )

  return (
    <div style={styles.area}>
      {/* Attachments */}
      {attachments.length > 0 && (
        <div style={styles.attachments}>
          {attachments.map((f, i) => (
            <div key={i} style={styles.attachment}>
              {f.preview && (
                <img src={f.preview} alt={f.name} style={styles.attachmentPreview} />
              )}
              <span style={styles.attachmentName}>{f.name}</span>
              <button style={styles.attachmentRemove} onClick={() => removeAttachment(i)}>
                ×
              </button>
            </div>
          ))}
        </div>
      )}

      {/* Input Container */}
      <div style={styles.inputContainer}>
        <textarea
          ref={textareaRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={
            isStreaming
              ? 'AI 正在响应中，点击暂停按钮可中断...'
              : '输入消息... (@提及文件, /命令, 粘贴图片)'
          }
          style={styles.textarea}
          rows={1}
          disabled={isStreaming}
        />
        <input
          ref={fileInputRef}
          type="file"
          multiple
          style={{ display: 'none' }}
          onChange={handleFilesSelected}
        />
        <div style={styles.actions}>
          <Tooltip title="添加附件">
            <button style={styles.actionBtn} onClick={() => fileInputRef.current?.click()}>
              <PaperClipOutlined />
            </button>
          </Tooltip>
          {isStreaming ? (
            <Tooltip title="暂停 AI 响应">
              <button style={{ ...styles.sendBtn, ...styles.streamingBtn }} onClick={() => useChat.getState().pauseStreaming()}>
                <PauseOutlined />
              </button>
            </Tooltip>
          ) : (
            <button
              style={{
                ...styles.sendBtn,
                ...(input.trim() ? styles.sendBtnActive : {}),
              }}
              onClick={sendMessage_}
              disabled={!input.trim()}
            >
              <SendOutlined />
            </button>
          )}
        </div>
      </div>

      {/* Permission Banner */}
      {permissionRequest && (
        <div style={styles.permissionBanner}>
          <div style={styles.permissionInfo}>
            <span>⚠️</span>
            <span>
              需要授权: <strong>{permissionRequest.tool || '未知工具'}</strong>
            </span>
          </div>
          <div style={styles.permissionActions}>
            <button style={{ ...styles.permBtn, ...styles.permDeny }} onClick={() => respondPermission('deny')}>
              拒绝
            </button>
            <button style={{ ...styles.permBtn, ...styles.permAllow }} onClick={() => respondPermission('allow')}>
              允许
            </button>
            <button style={{ ...styles.permBtn, ...styles.permAllowAll }} onClick={() => respondPermission('allow_always')}>
              始终允许
            </button>
          </div>
        </div>
      )}

      {/* Footer */}
      <div style={styles.footer}>
        <div style={styles.footerLeft}>
          {groupedModels.length > 0 && (
            <select
              value={selectedModel}
              onChange={(e) => setSelectedModel(e.target.value)}
              style={styles.selector}
              title="切换模型"
            >
              <option value="">自动模型</option>
              {groupedModels.map((group) => (
                <optgroup key={group.providerID} label={group.provider}>
                  {group.models.map((m) => (
                    <option key={m.id} value={`${group.providerID}/${m.id}`}>
                      {m.name || m.id}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
          )}
          <select
            value={selectedAgent}
            onChange={(e) => setSelectedAgent(e.target.value)}
            style={styles.selector}
            title="切换智能体"
          >
            {visibleAgents.map((a) => (
              <option key={a.name} value={a.name}>
                {AGENT_NAME_MAP[a.name] || a.name}
              </option>
            ))}
          </select>
          <select
            value={thinkingIntensity}
            onChange={(e) => setThinkingIntensity(e.target.value)}
            style={styles.selector}
            disabled={isStreaming}
            title="切换思考强度"
          >
            {THINKING_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
          <span style={styles.footerHint}>Enter 发送 · Shift+Enter 换行</span>
        </div>
        <div style={styles.footerRight}>
          {input.length > 0 && <span style={styles.charCount}>{input.length}</span>}
        </div>
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  area: {
    padding: '0 24px 16px',
    width: '100%',
    flexShrink: 0,
  },
  attachments: {
    display: 'flex',
    flexWrap: 'wrap',
    gap: 8,
    padding: '10px 0',
  },
  attachment: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    padding: '8px 14px',
    borderRadius: 10,
    background: 'linear-gradient(135deg, rgba(59, 130, 246, 0.08), rgba(99, 102, 241, 0.08))',
    fontSize: 12,
    color: 'var(--text-secondary)',
    border: '1px solid rgba(59, 130, 246, 0.15)',
  },
  attachmentPreview: {
    width: 40,
    height: 40,
    objectFit: 'cover',
    borderRadius: 6,
  },
  attachmentName: {
    maxWidth: 150,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  attachmentRemove: {
    background: 'none',
    border: 'none',
    color: 'var(--text-muted)',
    cursor: 'pointer',
    width: 18,
    height: 18,
    borderRadius: '50%',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: 14,
    padding: 0,
    lineHeight: 1,
  },
  inputContainer: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    background: 'var(--bg-secondary)',
    border: '1.5px solid var(--border)',
    borderRadius: 20,
    padding: '10px 16px',
    transition: 'all 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
    position: 'relative',
    boxShadow: '0 1px 2px rgba(0, 0, 0, 0.05)',
  },
  textarea: {
    flex: 1,
    background: 'none',
    border: 'none',
    color: 'var(--text-primary)',
    fontSize: 14.5,
    lineHeight: 1.5,
    resize: 'none',
    outline: 'none',
    fontFamily: 'inherit',
    minHeight: 24,
    maxHeight: 150,
    overflowY: 'hidden',
    padding: '2px 0',
    verticalAlign: 'top',
  },
  actions: {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    flexShrink: 0,
  },
  actionBtn: {
    background: 'none',
    border: 'none',
    color: 'var(--text-muted)',
    cursor: 'pointer',
    width: 36,
    height: 36,
    borderRadius: 10,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    transition: 'all 0.2s',
    fontSize: 18,
  },
  sendBtn: {
    width: 40,
    height: 40,
    background: 'var(--text-muted)',
    border: 'none',
    color: 'var(--bg-primary)',
    borderRadius: 12,
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    transition: 'all 0.2s',
    opacity: 0.3,
    fontSize: 18,
  },
  sendBtnActive: {
    background: 'linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)',
    opacity: 1,
    boxShadow: '0 4px 16px rgba(99, 102, 241, 0.4)',
  },
  streamingBtn: {
    background: 'linear-gradient(135deg, #ef4444 0%, #dc2626 100%)',
    opacity: 1,
    boxShadow: '0 4px 16px rgba(239, 68, 68, 0.4)',
  },
  permissionBanner: {
    marginTop: 12,
    padding: '14px 18px',
    borderRadius: 12,
    background: 'linear-gradient(135deg, rgba(245, 158, 11, 0.08), rgba(217, 119, 6, 0.08))',
    border: '1px solid rgba(245, 158, 11, 0.25)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 16,
  },
  permissionInfo: {
    display: 'flex',
    alignItems: 'center',
    gap: 12,
    fontSize: 13,
    color: 'var(--text-primary)',
  },
  permissionActions: {
    display: 'flex',
    gap: 10,
    flexShrink: 0,
  },
  permBtn: {
    padding: '8px 16px',
    borderRadius: 10,
    fontSize: 12,
    fontWeight: 500,
    cursor: 'pointer',
    border: '1px solid var(--border)',
    transition: 'all 0.2s',
  },
  permDeny: {
    background: 'var(--bg-tertiary)',
    color: 'var(--text-secondary)',
  },
  permAllow: {
    background: 'linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)',
    color: '#fff',
    borderColor: 'transparent',
  },
  permAllowAll: {
    background: 'linear-gradient(135deg, #22c55e 0%, #16a34a 100%)',
    color: '#fff',
    borderColor: 'transparent',
  },
  footer: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: '10px 4px 0',
  },
  footerLeft: {
    display: 'flex',
    alignItems: 'center',
    gap: 12,
  },
  footerRight: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
  },
  selector: {
    background: 'var(--bg-tertiary)',
    border: '1px solid var(--border)',
    color: 'var(--text-secondary)',
    borderRadius: 10,
    padding: '5px 10px',
    fontSize: 11,
    outline: 'none',
    maxWidth: 130,
    cursor: 'pointer',
    transition: 'all 0.2s',
  },
  footerHint: {
    fontSize: 11,
    color: 'var(--text-muted)',
    fontStyle: 'italic',
    opacity: 0.7,
  },
  charCount: {
    fontSize: 11,
    color: 'var(--text-muted)',
    fontFamily: "'JetBrains Mono', monospace",
    padding: '2px 8px',
    background: 'rgba(255, 255, 255, 0.03)',
    borderRadius: 6,
  },
}
