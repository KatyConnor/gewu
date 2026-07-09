import React, { useState, useMemo, useEffect, useCallback } from 'react'
import { useApp } from '../../stores/appStore'
import { useChat } from '../../stores/chatStore'

export default function ContextPanel() {
  const app = useApp()
  const chat = useChat()
  const [expandedRaw, setExpandedRaw] = useState<Set<string>>(new Set())
  const [, setRefreshKey] = useState(0)

  useEffect(() => {
    app.loadSessionStatus?.()
  }, [])

  useEffect(() => {
    setRefreshKey(k => k + 1)
  }, [app.messages.length, chat.streamingContent, chat.isStreaming])

  const toggleRaw = useCallback((id: string) => {
    setExpandedRaw(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }, [])

  const roleLabel = (role: string) => {
    const map: Record<string, string> = { user: '用户', assistant: '助手', system: '系统' }
    return map[role] || role
  }

  const formatTime = (ts?: number) => {
    if (!ts) return '-'
    try {
      const d = new Date(ts)
      if (isNaN(d.getTime())) return '-'
      return d.toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
    } catch {
      return '-'
    }
  }

  const formatJson = (msg: unknown) => {
    try { return JSON.stringify(msg, null, 2) } catch { return String(msg) }
  }

  const estimateTokens = (chars: number) => Math.ceil(chars / 4)

  const allMessages = useMemo(() => {
    const msgs = app.messages || []
    if (chat.isStreaming && chat.streamingContent) {
      const lastMsg = msgs[msgs.length - 1]
      if (lastMsg && lastMsg.role === 'assistant') return msgs
      return [...msgs, {
        id: chat.streamingMessageID || 'streaming',
        role: 'assistant' as const,
        content: chat.streamingContent,
        reasoning: '',
        toolCalls: [],
        streaming: true,
        time: { created: Date.now() },
        parts: [],
      }]
    }
    return msgs
  }, [app.messages, chat.isStreaming, chat.streamingContent, chat.streamingMessageID])

  const userMessages = useMemo(() => allMessages.filter(m => m.role === 'user'), [allMessages])
  const assistantMessages = useMemo(() => allMessages.filter(m => m.role === 'assistant'), [allMessages])

  const lastAssistantWithTokens = useMemo(() => {
    for (let i = assistantMessages.length - 1; i >= 0; i--) {
      const msg = assistantMessages[i]
      if (!msg.tokens) continue
      const t = msg.tokens
      const total = (t.input || 0) + (t.output || 0) + (t.reasoning || 0)
        + ((t.cache?.read || 0) + (t.cache?.write || 0))
      if (total > 0) return msg
    }
    return null
  }, [assistantMessages])

  const inputTokens = lastAssistantWithTokens?.tokens?.input || 0
  const outputTokens = lastAssistantWithTokens?.tokens?.output || 0
  const reasoningTokens = lastAssistantWithTokens?.tokens?.reasoning || 0
  const cacheRead = lastAssistantWithTokens?.tokens?.cache?.read || 0
  const cacheWrite = lastAssistantWithTokens?.tokens?.cache?.write || 0
  const totalTokens = inputTokens + outputTokens + reasoningTokens + cacheRead + cacheWrite

  const totalCost = useMemo(() => {
    let cost = 0
    for (const m of assistantMessages) cost += (m.cost || 0)
    if (cost === 0 && app.currentSession?.cost) cost = app.currentSession.cost
    return cost
  }, [assistantMessages, app.currentSession])

  const stats = [
    { label: '会话', value: app.currentSession?.title || app.currentSessionId || '-' },
    { label: '消息数', value: allMessages.length },
    { label: '总 Tokens', value: totalTokens > 0 ? totalTokens.toLocaleString() : (chat.isStreaming ? '计算中...' : '-') },
    { label: '输入 Tokens', value: inputTokens > 0 ? inputTokens.toLocaleString() : '-' },
    { label: '输出 Tokens', value: outputTokens > 0 ? outputTokens.toLocaleString() : '-' },
    { label: '推理 Tokens', value: reasoningTokens > 0 ? reasoningTokens.toLocaleString() : '-' },
    { label: '缓存 Tokens', value: (cacheRead + cacheWrite) > 0 ? `${cacheRead.toLocaleString()} / ${cacheWrite.toLocaleString()}` : '-' },
    { label: '用户消息', value: userMessages.length },
    { label: '助手消息', value: assistantMessages.length },
    { label: '总费用', value: totalCost > 0 ? `$${totalCost.toFixed(4)}` : '-' },
  ]

  const sortedMessages = useMemo(() => {
    return [...allMessages].sort((a, b) => (b.time?.created || 0) - (a.time?.created || 0))
  }, [allMessages])

  return (
    <div style={{ padding: 12 }}>
      <div style={{ marginBottom: 16 }}>
        <div style={{
          fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase',
          letterSpacing: 0.5, marginBottom: 8, fontWeight: 500,
        }}>统计信息</div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6 }}>
          {stats.map(s => (
            <div key={s.label} style={{ padding: '6px 8px', borderRadius: 6, background: 'var(--bg-primary)' }}>
              <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 2 }}>{s.label}</div>
              <div style={{
                fontSize: 13, color: 'var(--text-secondary)', fontWeight: 500,
                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
              }}>{typeof s.value === 'number' ? s.value.toLocaleString() : s.value}</div>
            </div>
          ))}
        </div>
      </div>

      <div style={{ marginBottom: 16 }}>
        <div style={{
          fontSize: 11, color: 'var(--text-muted)', textTransform: 'uppercase',
          letterSpacing: 0.5, marginBottom: 8, fontWeight: 500,
        }}>
          原始消息 <span style={{ textTransform: 'none', letterSpacing: 0, fontWeight: 400 }}>({sortedMessages.length})</span>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          {sortedMessages.map(msg => (
            <div key={msg.id} style={{ borderRadius: 6, overflow: 'hidden' }}>
              <button
                onClick={() => toggleRaw(msg.id)}
                style={{
                  display: 'flex', alignItems: 'center', gap: 6, width: '100%',
                  padding: '6px 8px', background: 'var(--bg-primary)', border: 'none',
                  cursor: 'pointer', fontSize: 12, textAlign: 'left',
                }}
              >
                <span style={{
                  padding: '1px 6px', borderRadius: 4, fontSize: 10, fontWeight: 600,
                  letterSpacing: 0.3, flexShrink: 0,
                  background: msg.role === 'user' ? '#22c55e22' : msg.role === 'assistant' ? '#3b82f622' : '#f59e0b22',
                  color: msg.role === 'user' ? '#22c55e' : msg.role === 'assistant' ? '#3b82f6' : '#f59e0b',
                }}>{roleLabel(msg.role)}</span>
                <span style={{
                  color: 'var(--text-muted)', fontFamily: 'monospace', fontSize: 11,
                  flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                }}>{msg.id}</span>
                <span style={{ color: 'var(--text-muted)', fontSize: 11, flexShrink: 0 }}>
                  {formatTime(msg.time?.created)}
                </span>
                <span style={{ color: 'var(--text-muted)', fontSize: 10, width: 12, textAlign: 'center', flexShrink: 0 }}>
                  {expandedRaw.has(msg.id) ? '▼' : '▶'}
                </span>
              </button>
              {expandedRaw.has(msg.id) && (
                <div style={{ padding: '0 8px 8px' }}>
                  <pre style={{
                    background: 'var(--bg-primary)', border: '1px solid var(--border)',
                    borderRadius: 6, padding: '10px 12px', margin: 0,
                    fontSize: 11, fontFamily: "'JetBrains Mono', monospace",
                    color: 'var(--text-secondary)', overflowX: 'auto', maxHeight: 300,
                    overflowY: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                    lineHeight: 1.4,
                  }}>{formatJson(msg)}</pre>
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
