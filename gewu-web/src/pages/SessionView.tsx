import React, { useEffect, useCallback, useRef } from 'react'
import { useParams } from 'react-router-dom'
import { useApp } from '../stores/appStore'
import { useChat } from '../stores/chatStore'
import { sessionApi, subscribeSessionEvents } from '../api/chat'
import MessageTimeline from '../components/chat/MessageTimeline'
import PromptInput from '../components/chat/PromptInput'

export default function SessionView() {
  const { id } = useParams<{ id: string }>()
  const appStore = useApp()
  const chatStore = useChat()
  const eventSourceRef = useRef<EventSource | null>(null)

  const showProgress = useApp((s) => s.showProgress)
  const isStreaming = useChat((s) => s.isStreaming)

  const checkAndResumeStreaming = useCallback(async (sessionId: string) => {
    if (!sessionId) return

    try {
      const statusData = await sessionApi.status() as Record<string, { type: string }>
      const sessionStatus = statusData?.[sessionId]

      if (sessionStatus?.type === 'busy') {
        console.log('Session is busy, resuming streaming:', sessionId)
        await useChat.getState().resumeStreaming(sessionId)
      } else {
        console.log('Session is idle, showing completed state:', sessionId)
        if (useChat.getState().isStreaming) {
          useChat.getState().abortStreaming()
        }
      }
    } catch (e) {
      console.warn('Failed to check session status:', e)
    }
  }, [])

  useEffect(() => {
    if (id) {
      appStore.selectSession(id)
      checkAndResumeStreaming(id)
    }
  }, [id, appStore, checkAndResumeStreaming])

  useEffect(() => {
    return () => {
      chatStore.abortStreaming()
    }
  }, [chatStore])

  return (
    <div style={styles.sessionView}>
      {/* Progress Bar */}
      {showProgress && isStreaming && (
        <div style={styles.progressBarContainer}>
          <div style={styles.progressBar} />
        </div>
      )}

      <div style={styles.sessionMain}>
        <MessageTimeline />
        <PromptInput />
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  sessionView: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    minHeight: 0,
  },
  sessionMain: {
    flex: 1,
    display: 'flex',
    flexDirection: 'column',
    minHeight: 0,
  },
  progressBarContainer: {
    height: 3,
    background: 'var(--border)',
    overflow: 'hidden',
    flexShrink: 0,
  },
  progressBar: {
    height: '100%',
    width: '40%',
    background: 'linear-gradient(90deg, #6366f1, #8b5cf6, #6366f1)',
    borderRadius: 2,
    animation: 'progressSlide 1.5s ease-in-out infinite',
  },
}
