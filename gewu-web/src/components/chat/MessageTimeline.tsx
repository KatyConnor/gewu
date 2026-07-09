import React, { useRef, useEffect, useState, useCallback, useMemo } from 'react'
import { useApp } from '../../stores/appStore'
import { useChat } from '../../stores/chatStore'
import MessageItem from './MessageItem'
import type { Message } from '../../api/chat'

const BOTTOM_THRESHOLD = 80

export default function MessageTimeline() {
  const scrollRef = useRef<HTMLDivElement>(null)
  const contentRef = useRef<HTMLDivElement>(null)
  const bottomAnchorRef = useRef<HTMLDivElement>(null)

  const appMessages = useApp((s) => s.messages)
  const isStreaming = useChat((s) => s.isStreaming)
  const streamingContent = useChat((s) => s.streamingContent)
  const streamingReasoning = useChat((s) => s.streamingReasoning)
  const streamingToolCalls = useChat((s) => s.streamingToolCalls)
  const error = useChat((s) => s.error)
  const pendingUserMessage = useChat((s) => s.pendingUserMessage)

  const [showJumpBtn, setShowJumpBtn] = useState(false)
  const [newMsgCount, setNewMsgCount] = useState(0)

  const userScrolledRef = useRef(false)
  const isAutoScrollingRef = useRef(false)
  const prevMsgCountRef = useRef(0)

  const displayMessages = useMemo(() => {
    const pending = pendingUserMessage
    const serverMsgs = appMessages || []
    if (!pending) return serverMsgs
    const filtered = serverMsgs.filter((m) => m.role !== 'user')
    return [...filtered, pending]
  }, [appMessages, pendingUserMessage])

  const emptyStreamingMsg = useMemo<Message>(
    () => ({ role: 'assistant', content: '', streaming: true }),
    []
  )

  const streamingMsg = useMemo<Message>(
    () => ({
      role: 'assistant',
      content: streamingContent,
      reasoning: streamingReasoning,
      toolCalls: streamingToolCalls.map((tc) => ({
        name: tc.name,
        status: tc.status,
        output: tc.output,
        error: tc.error,
      })),
      streaming: true,
    }),
    [streamingContent, streamingReasoning, streamingToolCalls]
  )

  const distanceFromBottom = useCallback(() => {
    const el = scrollRef.current
    if (!el) return 0
    return el.scrollHeight - el.clientHeight - el.scrollTop
  }, [])

  const canScroll = useCallback(() => {
    const el = scrollRef.current
    if (!el) return false
    return el.scrollHeight - el.clientHeight > 1
  }, [])

  const scrollToBottomNow = useCallback(() => {
    const el = scrollRef.current
    if (!el) return
    el.scrollTop = el.scrollHeight
  }, [])

  const scrollToBottom = useCallback(
    (force = false) => {
      if (force && userScrolledRef.current) userScrolledRef.current = false
      if (!force && userScrolledRef.current) return
      if (!force && !canScroll()) return

      isAutoScrollingRef.current = true
      scrollToBottomNow()
    },
    [canScroll, scrollToBottomNow]
  )

  const forceScrollToBottom = useCallback(() => {
    userScrolledRef.current = false
    setNewMsgCount(0)
    scrollToBottom(true)
  }, [scrollToBottom])

  const onScroll = useCallback(() => {
    if (isAutoScrollingRef.current) {
      isAutoScrollingRef.current = false
      return
    }

    if (!canScroll()) {
      if (userScrolledRef.current) userScrolledRef.current = false
      return
    }

    if (distanceFromBottom() < BOTTOM_THRESHOLD) {
      if (userScrolledRef.current) userScrolledRef.current = false
      setShowJumpBtn(false)
      return
    }

    setShowJumpBtn(true)
    userScrolledRef.current = true
  }, [canScroll, distanceFromBottom])

  // ResizeObserver to auto-scroll during streaming
  useEffect(() => {
    const contentEl = contentRef.current
    if (!contentEl) return

    const observer = new ResizeObserver(() => {
      if (userScrolledRef.current) return
      if (!isStreaming) return
      scrollToBottom(false)
    })

    observer.observe(contentEl)
    return () => observer.disconnect()
  }, [isStreaming, scrollToBottom])

  // Auto-scroll when streaming starts
  useEffect(() => {
    if (isStreaming) {
      userScrolledRef.current = false
      scrollToBottom(true)
    }
  }, [isStreaming, scrollToBottom])

  // Auto-scroll on streaming content changes
  useEffect(() => {
    if (!userScrolledRef.current && isStreaming) scrollToBottom(false)
  }, [streamingContent, streamingReasoning, isStreaming, scrollToBottom])

  useEffect(() => {
    if (!userScrolledRef.current && isStreaming) scrollToBottom(false)
  }, [streamingToolCalls.length, isStreaming, scrollToBottom])

  // Track new messages while scrolled up
  useEffect(() => {
    const newLen = appMessages?.length || 0
    if (newLen > prevMsgCountRef.current) {
      if (userScrolledRef.current) {
        setNewMsgCount((c) => c + (newLen - prevMsgCountRef.current))
      } else {
        scrollToBottom(false)
        setNewMsgCount(0)
      }
    }
    prevMsgCountRef.current = newLen
  }, [appMessages?.length, scrollToBottom])

  const isEmpty = !displayMessages.length && !isStreaming

  return (
    <div ref={scrollRef} style={styles.timeline} onScroll={onScroll}>
      <div ref={contentRef} style={styles.content}>
        {isEmpty && (
          <div style={styles.emptyState}>
            <div style={styles.emptyContent}>
              <div style={styles.emptyLogoWrap}>
                <div style={styles.emptyLogoGlow} />
                <img src="/gewu-logo.jpg" alt="logo" style={styles.emptyLogo} />
              </div>
              <div style={styles.emptyTitle}>
                格物<span style={styles.titleDot}>·</span>致虚
              </div>
              <div style={styles.emptyDivider} />
              <div style={styles.emptyHint}>AI 智能体编程，格物求实 · 致虚观道</div>
              <div style={styles.emptySub}>输入消息开始智能对话，让 AI 助你高效编程</div>
            </div>
          </div>
        )}

        {displayMessages.map((msg, i) => (
          <div key={(msg as Record<string, unknown>).id || i} style={styles.messageWrapper}>
            <MessageItem message={msg} />
          </div>
        ))}

        {isStreaming && !streamingContent && !streamingReasoning && !streamingToolCalls.length && (
          <div style={styles.messageWrapper}>
            <MessageItem message={emptyStreamingMsg} />
          </div>
        )}

        {isStreaming &&
          (streamingContent || streamingReasoning || streamingToolCalls.length > 0) && (
            <div style={styles.messageWrapper}>
              <MessageItem message={streamingMsg} />
            </div>
          )}

        {error && (
          <div style={styles.errorBanner}>
            <span>⚠️</span>
            <span style={{ flex: 1 }}>{error}</span>
          </div>
        )}
      </div>
      <div ref={bottomAnchorRef} />

      {showJumpBtn && (
        <button style={styles.jumpToBottom} onClick={forceScrollToBottom} title="跳转到最新消息">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <polyline points="6 9 12 15 18 9" />
          </svg>
          {newMsgCount > 0 && (
            <span style={styles.newMsgBadge}>
              {newMsgCount > 99 ? '99+' : newMsgCount}
            </span>
          )}
        </button>
      )}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  timeline: {
    flex: 1,
    overflowY: 'auto',
    padding: '24px 24px 0',
    position: 'relative',
  },
  content: {
    display: 'flex',
    flexDirection: 'column',
    minHeight: '100%',
  },
  messageWrapper: {},
  emptyState: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: '100%',
    color: 'var(--text-muted)',
    position: 'relative',
    overflow: 'hidden',
    flex: 1,
  },
  emptyContent: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    position: 'relative',
    zIndex: 1,
  },
  emptyLogoWrap: {
    position: 'relative',
    marginBottom: 20,
    animation: 'floatLogo 4s ease-in-out infinite',
  },
  emptyLogoGlow: {
    position: 'absolute',
    inset: -6,
    borderRadius: 18,
    background: 'linear-gradient(135deg, var(--accent) 0%, transparent 50%)',
    opacity: 0.2,
    filter: 'blur(10px)',
  },
  emptyLogo: {
    width: 72,
    height: 72,
    borderRadius: 'var(--radius-lg)',
    objectFit: 'contain',
    boxShadow: 'var(--shadow-lg)',
  },
  emptyTitle: {
    fontSize: 26,
    fontWeight: 700,
    color: 'var(--text-primary)',
    letterSpacing: 4,
    background: 'linear-gradient(135deg, var(--text-primary) 0%, var(--accent) 40%, var(--text-primary) 80%)',
    backgroundSize: '200% auto',
    WebkitBackgroundClip: 'text',
    WebkitTextFillColor: 'transparent',
    backgroundClip: 'text',
  },
  titleDot: {
    WebkitTextFillColor: 'var(--accent)',
    opacity: 0.9,
    filter: 'drop-shadow(0 0 6px var(--accent-glow))',
  },
  emptyDivider: {
    width: 48,
    height: 2,
    background: 'linear-gradient(90deg, transparent, var(--accent), transparent)',
    margin: '16px 0',
  },
  emptyHint: {
    fontSize: 14,
    color: 'var(--text-secondary)',
    fontFamily: "'Noto Serif SC', 'STKaiti', serif",
    letterSpacing: 2,
  },
  emptySub: {
    fontSize: 12,
    color: 'var(--text-muted)',
    marginTop: 8,
  },
  jumpToBottom: {
    position: 'sticky',
    bottom: 16,
    left: '50%',
    transform: 'translateX(-50%)',
    display: 'flex',
    alignItems: 'center',
    gap: 6,
    padding: '8px 18px',
    borderRadius: 'var(--radius-full)',
    background: 'var(--bg-elevated)',
    border: '1px solid var(--border)',
    color: 'var(--text-secondary)',
    cursor: 'pointer',
    boxShadow: 'var(--shadow-md)',
    zIndex: 10,
    width: 'fit-content',
    margin: '0 auto',
  },
  newMsgBadge: {
    background: 'var(--accent)',
    color: 'var(--text-inverse)',
    padding: '2px 8px',
    borderRadius: 'var(--radius-full)',
    fontSize: 11,
    fontWeight: 600,
  },
  errorBanner: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    padding: '12px 16px',
    margin: '10px 18px',
    background: 'var(--danger-bg)',
    border: '1px solid rgba(239, 107, 107, 0.2)',
    borderRadius: 'var(--radius-md)',
    color: 'var(--danger)',
    fontSize: 13,
  },
}
