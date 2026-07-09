import React from 'react'
import { useApp } from '../../stores/appStore'
import { useChat } from '../../stores/chatStore'
import ChatHeader from './ChatHeader'
import MessageTimeline from './MessageTimeline'
import PromptInput from './PromptInput'

export default function ChatArea() {
  const currentSession = useApp((s) => s.currentSession)

  if (!currentSession) {
    return (
      <div style={styles.welcome}>
        <div style={styles.welcomeInner}>
          <img src="/gewu-logo.jpg" alt="logo" style={styles.welcomeLogo} />
          <h1 style={styles.brandTitle}>
            格物<span style={styles.titleDot}>·</span>致虚
          </h1>
          <p style={styles.subtitle}>AI 智能体交互界面</p>
          <p style={styles.welcomeSub}>基于 OpenCode 构建 · 从左侧选择或创建一个会话开始</p>
        </div>
      </div>
    )
  }

  return (
    <div style={styles.chatArea}>
      <ChatHeader />
      <MessageTimeline />
      <PromptInput />
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  chatArea: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    minHeight: 0,
  },
  welcome: {
    flex: 1,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    background: 'radial-gradient(ellipse at center, var(--bg-secondary) 0%, var(--bg-primary) 70%)',
  },
  welcomeInner: {
    textAlign: 'center',
    color: 'var(--text-muted)',
  },
  welcomeLogo: {
    width: 84,
    height: 84,
    borderRadius: 'var(--radius-lg)',
    objectFit: 'contain',
    marginBottom: 24,
    boxShadow: 'var(--shadow-lg)',
  },
  brandTitle: {
    fontSize: 38,
    fontWeight: 700,
    color: 'var(--text-primary)',
    margin: '0 0 12px',
    letterSpacing: 5,
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
  subtitle: {
    margin: '6px 0',
    fontSize: 16,
    color: 'var(--text-secondary)',
  },
  welcomeSub: {
    fontSize: 13,
    color: 'var(--text-muted)',
    marginTop: 14,
  },
}
