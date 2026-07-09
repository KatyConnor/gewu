import React, { useState, useCallback, useMemo } from 'react'
import { renderMarkdown, formatJson } from '../../utils/markdown'
import { usePacedText } from '../../hooks/usePacedText'
import type { Message } from '../../api/chat'

interface MessageItemProps {
  message: Message
}

export default function MessageItem({ message }: MessageItemProps) {
  const [copied, setCopied] = useState(false)

  const role = message.info?.role || message.role || 'assistant'

  const roleLabel = useMemo(() => {
    if (role === 'user') return '你'
    if (role === 'assistant') return 'AI'
    return role
  }, [role])

  const textParts = useMemo(
    () => (message.parts || []).filter((p) => p.type === 'text'),
    [message.parts]
  )

  const toolParts = useMemo(
    () => (message.parts || []).filter((p) => p.type === 'tool-invocation'),
    [message.parts]
  )

  const stepParts = useMemo(
    () => (message.parts || []).filter((p) => p.type === 'step-start'),
    [message.parts]
  )

  const { displayed: pacedContent } = usePacedText(
    message.content || '',
    !!message.streaming
  )

  const contentHtml = useMemo(
    () => renderMarkdown(message.streaming ? pacedContent : message.content || ''),
    [message.streaming, pacedContent, message.content]
  )

  const copyContent = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(message.content || '')
    } catch {
      const ta = document.createElement('textarea')
      ta.value = message.content || ''
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }, [message.content])

  const isUser = role === 'user'

  return (
    <div style={{ ...styles.message, flexDirection: isUser ? 'row-reverse' : 'row' }}>
      <div
        style={{
          ...styles.avatar,
          background: isUser ? 'var(--accent)' : 'var(--bg-tool)',
        }}
      >
        {isUser ? '👤' : '🤖'}
      </div>
      <div
        style={{
          ...styles.content,
          textAlign: isUser ? 'right' : 'left',
        }}
      >
        <div style={styles.roleLabel}>{roleLabel}</div>
        <div
          style={{
            ...styles.body,
            background: isUser ? 'var(--accent)' : 'var(--bg-msg)',
            color: isUser ? '#fff' : 'var(--text-primary)',
            borderColor: isUser ? 'var(--accent)' : 'var(--border)',
          }}
        >
          {textParts.length > 0 && (
            <div
              style={styles.markdownBody}
              dangerouslySetInnerHTML={{ __html: contentHtml }}
            />
          )}

          {toolParts.map((part, i) => (
            <div key={`tool-${i}`} style={styles.toolInvocation}>
              <div style={styles.toolHeader}>
                <span>🔧</span>
                <span style={styles.toolName}>
                  {(part as Record<string, unknown>).toolInvocation &&
                    (part as Record<string, Record<string, unknown>>).toolInvocation.tool}
                </span>
              </div>
              <pre style={styles.toolArgs}>
                {formatJson(
                  (part as Record<string, Record<string, unknown>>).toolInvocation?.args
                )}
              </pre>
            </div>
          ))}

          {stepParts.map((part, i) => (
            <div key={`step-${i}`} style={styles.stepInfo}>
              <span>⏳</span>
              {(part as Record<string, string>).title || '处理中...'}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  message: {
    display: 'flex',
    gap: 12,
    padding: '8px 0',
  },
  avatar: {
    width: 36,
    height: 36,
    borderRadius: 8,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: 18,
    flexShrink: 0,
  },
  content: {
    flex: 1,
    minWidth: 0,
  },
  roleLabel: {
    fontSize: 12,
    fontWeight: 600,
    color: 'var(--text-muted)',
    marginBottom: 4,
  },
  body: {
    background: 'var(--bg-msg)',
    border: '1px solid var(--border)',
    borderRadius: 12,
    padding: '14px 18px',
    fontSize: 14,
    lineHeight: 1.7,
    color: 'var(--text-primary)',
    position: 'relative',
  },
  markdownBody: {
    wordBreak: 'break-word',
  },
  toolInvocation: {
    marginTop: 10,
    background: 'var(--bg-tool)',
    border: '1px solid var(--border)',
    borderRadius: 8,
    padding: '10px 14px',
  },
  toolHeader: {
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    fontSize: 13,
    fontWeight: 600,
    color: 'var(--accent)',
    marginBottom: 6,
  },
  toolName: {},
  toolArgs: {
    margin: 0,
    fontSize: 12,
    color: 'var(--text-secondary)',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
    maxHeight: 200,
    overflowY: 'auto',
  },
  stepInfo: {
    marginTop: 8,
    fontSize: 13,
    color: 'var(--text-muted)',
    display: 'flex',
    alignItems: 'center',
    gap: 6,
  },
}
