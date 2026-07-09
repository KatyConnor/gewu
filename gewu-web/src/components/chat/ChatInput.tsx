import React, { useState, useRef, useCallback, useEffect } from 'react'
import { useApp } from '../../stores/appStore'
import { useChat } from '../../stores/chatStore'

const THINKING_OPTIONS = [
  { value: 'none', label: '关', desc: '关闭深度思考' },
  { value: 'low', label: '浅', desc: '轻度推理' },
  { value: 'medium', label: '中', desc: '标准推理' },
  { value: 'high', label: '深', desc: '深度推理，更全面的分析' },
]

export default function ChatInput() {
  const [input, setInput] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const currentSessionId = useApp((s) => s.currentSessionId)
  const isStreaming = useChat((s) => s.isStreaming)
  const thinkingIntensity = useChat((s) => s.thinkingIntensity)
  const setThinkingIntensity = useChat((s) => s.setThinkingIntensity)
  const sendMessage = useChat((s) => s.sendMessage)
  const executeCommand = useChat((s) => s.executeCommand)

  const canSend = input.trim() && !isStreaming && !!currentSessionId

  const placeholder = isStreaming
    ? 'AI 正在思考...'
    : !currentSessionId
    ? '请先选择一个会话'
    : '输入消息... (Enter 发送)'

  const autoResize = useCallback(() => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 160) + 'px'
  }, [])

  useEffect(() => {
    autoResize()
  }, [input, autoResize])

  const handleKeydown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        handleSend()
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [canSend, input, currentSessionId, thinkingIntensity]
  )

  const handleSend = useCallback(() => {
    if (!canSend || !currentSessionId) return

    const content = input.trim()
    setInput('')

    requestAnimationFrame(() => autoResize())

    if (content.startsWith('/')) {
      const parts = content.slice(1).split(' ')
      const command = parts[0]
      const args = parts.slice(1).join(' ')
      executeCommand(currentSessionId, command, args)
    } else {
      sendMessage(currentSessionId, content, undefined, undefined, thinkingIntensity)
    }
  }, [canSend, input, currentSessionId, thinkingIntensity, sendMessage, executeCommand, autoResize])

  return (
    <div style={styles.chatInput}>
      <div style={styles.inputWrapper}>
        <textarea
          ref={textareaRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeydown}
          placeholder={placeholder}
          disabled={isStreaming}
          rows={1}
          style={styles.inputField}
        />
        <button
          style={{
            ...styles.btnSend,
            opacity: canSend ? 1 : 0.4,
            cursor: canSend ? 'pointer' : 'not-allowed',
          }}
          onClick={handleSend}
          disabled={!canSend}
          title={isStreaming ? 'AI 正在响应...' : '发送 (Enter)'}
        >
          {isStreaming ? <span style={styles.spinner} /> : <span>↑</span>}
        </button>
      </div>
      <div style={styles.inputHint}>
        Enter 发送 · Shift+Enter 换行 · <code style={styles.hintCode}>/</code> 命令
      </div>
      <div style={styles.thinkingBar}>
        <span style={styles.thinkingLabel}>思考</span>
        <div style={styles.thinkingOptions}>
          {THINKING_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              style={{
                ...styles.thinkingBtn,
                ...(thinkingIntensity === opt.value ? styles.thinkingBtnActive : {}),
              }}
              onClick={() => setThinkingIntensity(opt.value)}
              disabled={isStreaming}
              title={opt.desc}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  chatInput: {
    padding: '16px 24px 20px',
    borderTop: '1px solid var(--border)',
    background: 'var(--bg-secondary)',
  },
  inputWrapper: {
    maxWidth: 860,
    margin: '0 auto',
    display: 'flex',
    alignItems: 'flex-end',
    gap: 10,
    background: 'var(--bg-tertiary)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius-lg)',
    padding: '8px 8px 8px 18px',
    transition: 'all var(--transition-fast)',
  },
  inputField: {
    flex: 1,
    border: 'none',
    background: 'transparent',
    color: 'var(--text-primary)',
    fontSize: 14,
    lineHeight: 1.6,
    resize: 'none',
    outline: 'none',
    fontFamily: 'inherit',
    padding: '8px 0',
    maxHeight: 160,
  },
  btnSend: {
    width: 38,
    height: 38,
    border: 'none',
    background: 'linear-gradient(135deg, var(--accent) 0%, #2db87a 100%)',
    color: 'var(--text-inverse)',
    borderRadius: 'var(--radius-md)',
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: 18,
    flexShrink: 0,
    transition: 'all var(--transition-fast)',
    boxShadow: 'var(--shadow-xs)',
  },
  spinner: {
    width: 16,
    height: 16,
    border: '2px solid rgba(255,255,255,0.3)',
    borderTopColor: '#fff',
    borderRadius: '50%',
    animation: 'spin 0.8s linear infinite',
    display: 'inline-block',
  },
  inputHint: {
    maxWidth: 860,
    margin: '10px auto 0',
    fontSize: 11,
    color: 'var(--text-muted)',
    textAlign: 'center',
  },
  hintCode: {
    background: 'var(--bg-elevated)',
    padding: '2px 6px',
    borderRadius: 'var(--radius-sm)',
    fontSize: 11,
    fontFamily: "'JetBrains Mono', monospace",
    color: 'var(--text-secondary)',
  },
  thinkingBar: {
    maxWidth: 860,
    margin: '8px auto 0',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
  },
  thinkingLabel: {
    fontSize: 11,
    color: 'var(--text-muted)',
    fontFamily: "'Noto Serif SC', serif",
    letterSpacing: 0.5,
  },
  thinkingOptions: {
    display: 'flex',
    gap: 2,
    background: 'var(--bg-tertiary)',
    borderRadius: 'var(--radius-sm)',
    padding: 2,
  },
  thinkingBtn: {
    border: 'none',
    background: 'transparent',
    color: 'var(--text-muted)',
    fontSize: 11,
    padding: '3px 10px',
    borderRadius: 'var(--radius-xs)',
    cursor: 'pointer',
    transition: 'all var(--transition-fast)',
    fontFamily: "'Noto Sans SC', sans-serif",
  },
  thinkingBtnActive: {
    background: 'var(--accent)',
    color: 'var(--text-inverse)',
    boxShadow: '0 0 8px var(--accent-muted)',
  },
}
