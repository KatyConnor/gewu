import React, { useRef, useEffect, useMemo, useState } from 'react'
import { useApp } from '../../stores/appStore'
import { useChat } from '../../stores/chatStore'
import MessageItem from './MessageItem'
import type { Message } from '../../api/chat'

export default function MessageList() {
  const containerRef = useRef<HTMLDivElement>(null)
  const messages = useChat((s) => s.messages)
  const isStreaming = useChat((s) => s.isStreaming)
  const streamingContent = useChat((s) => s.streamingContent)
  const error = useChat((s) => s.error)
  const currentSessionId = useApp((s) => s.currentSessionId)

  useEffect(() => {
    if (containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight
    }
  }, [messages.length, streamingContent])

  return (
    <div ref={containerRef} style={styles.messageList}>
      <div style={styles.messagesInner}>
        {messages.map((msg) => (
          <MessageItem key={msg.id || msg.info?.id || Math.random()} message={msg} />
        ))}

        {isStreaming && (
          <div style={styles.streamMessage}>
            {streamingContent ? (
              <div
                style={styles.streamContent}
                dangerouslySetInnerHTML={{ __html: streamingContent }}
              />
            ) : (
              <div style={styles.typingIndicator}>
                <span style={styles.dot} />
                <span style={{ ...styles.dot, animationDelay: '0.16s' }} />
                <span style={{ ...styles.dot, animationDelay: '0.32s' }} />
              </div>
            )}
          </div>
        )}
      </div>

      {error && (
        <div style={styles.errorBanner}>
          {error}
          <button style={styles.errorClose}>✕</button>
        </div>
      )}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  messageList: {
    flex: 1,
    overflowY: 'auto',
    padding: 24,
    position: 'relative',
  },
  messagesInner: {
    maxWidth: 860,
    margin: '0 auto',
    display: 'flex',
    flexDirection: 'column',
    gap: 16,
  },
  streamMessage: {
    display: 'flex',
    flexDirection: 'column',
    gap: 12,
  },
  streamContent: {
    fontSize: 14,
    lineHeight: 1.7,
    color: 'var(--text-primary)',
  },
  typingIndicator: {
    display: 'flex',
    gap: 4,
    padding: '4px 0',
  },
  dot: {
    width: 6,
    height: 6,
    borderRadius: '50%',
    background: 'var(--text-muted)',
    animation: 'bounce 1.4s infinite ease-in-out',
    display: 'inline-block',
  },
  errorBanner: {
    position: 'sticky',
    bottom: 0,
    margin: '16px -24px -24px',
    padding: '12px 24px',
    background: 'var(--danger)',
    color: '#fff',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    fontSize: 13,
  },
  errorClose: {
    background: 'transparent',
    border: 'none',
    color: '#fff',
    cursor: 'pointer',
    fontSize: 16,
  },
}
