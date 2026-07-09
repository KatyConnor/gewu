import { useState, useEffect, useCallback, useRef } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { Button, Tag, Spin, Input, Avatar, message } from 'antd'
import { ArrowLeftOutlined, SendOutlined, UserOutlined } from '@ant-design/icons'
import { get, post, SessionDTO, MessageDTO, UserDTO, PageResult } from '../services/api'
import dayjs from 'dayjs'

const typeMap: Record<number, { text: string; color: string }> = {
  0: { text: '群聊', color: 'blue' },
  1: { text: '私聊', color: 'cyan' },
  2: { text: 'AI辅助', color: 'purple' },
}

interface MessageBubbleProps {
  message: MessageDTO
  isSelf: boolean
}

function MessageBubble({ message, isSelf }: MessageBubbleProps) {
  return (
    <div style={{ display: 'flex', justifyContent: isSelf ? 'flex-end' : 'flex-start', marginBottom: 12 }}>
      {!isSelf && <Avatar icon={<UserOutlined />} style={{ marginRight: 8, flexShrink: 0 }} />}
      <div style={{ maxWidth: '70%' }}>
        <div style={{ fontSize: 12, color: '#999', marginBottom: 4, textAlign: isSelf ? 'right' : 'left' }}>
          {message.senderName || message.senderId} · {dayjs(message.createdAt).format('HH:mm:ss')}
        </div>
        {message.messageType && message.messageType !== 'text' && (
          <Tag style={{ marginBottom: 4 }}>{message.messageType}</Tag>
        )}
        <div
          style={{
            padding: '8px 12px',
            borderRadius: 8,
            background: isSelf ? '#1677ff' : '#f5f5f5',
            color: isSelf ? '#fff' : '#333',
            wordBreak: 'break-word',
            whiteSpace: 'pre-wrap',
          }}
        >
          {message.content}
        </div>
      </div>
      {isSelf && <Avatar icon={<UserOutlined />} style={{ marginLeft: 8, flexShrink: 0, background: '#1677ff' }} />}
    </div>
  )
}

export default function SessionDetail() {
  const { sessionId } = useParams<{ sessionId: string }>()
  const navigate = useNavigate()
  const [session, setSession] = useState<SessionDTO | null>(null)
  const [messages, setMessages] = useState<MessageDTO[]>([])
  const [loading, setLoading] = useState(true)
  const [currentUserId, setCurrentUserId] = useState<string>('')
  const [inputValue, setInputValue] = useState('')
  const [sending, setSending] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)
  const eventSourceRef = useRef<EventSource | null>(null)

  const scrollToBottom = useCallback(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [])

  const fetchMessages = useCallback(async () => {
    if (!sessionId) return
    try {
      const result = await get<PageResult<MessageDTO>>(`/sessions/${sessionId}/messages?page=1&size=50`)
      if (result.success) {
        setMessages(result.data.records)
        setTimeout(scrollToBottom, 100)
      }
    } catch (error: any) {
      message.error(error.message || '获取消息列表失败')
    }
  }, [sessionId, scrollToBottom])

  const fetchCurrentUser = useCallback(async () => {
    try {
      const result = await get<UserDTO>('/users/me')
      if (result.success) {
        setCurrentUserId(result.data.userId)
      }
    } catch {
      // ignore - bubble alignment defaults to left
    }
  }, [])

  useEffect(() => {
    if (!sessionId) return
    let active = true
    setLoading(true)
    Promise.all([
      get<SessionDTO>(`/sessions/${sessionId}`).catch(() => null),
      fetchCurrentUser(),
    ]).then(([sessionResult]) => {
      if (!active) return
      if (sessionResult && sessionResult.success) {
        setSession(sessionResult.data)
      }
      Promise.all([
        fetchMessages(),
      ]).then(() => {
        if (active) setLoading(false)
      })
    })
    return () => {
      active = false
    }
  }, [sessionId, fetchCurrentUser, fetchMessages])

  useEffect(() => {
    if (!sessionId) return
    const token = localStorage.getItem('accessToken')
    const url = `/api/v1/sse/sessions/${sessionId}?token=${token}`
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null

    const connect = () => {
      const es = new EventSource(url)
      eventSourceRef.current = es

      es.addEventListener('message', (event) => {
        try {
          const data: MessageDTO = JSON.parse(event.data)
          setMessages((prev) => {
            if (prev.some((m) => m.messageId === data.messageId)) return prev
            return [...prev, data]
          })
          setTimeout(scrollToBottom, 50)
        } catch {
          // ignore malformed payload
        }
      })

      es.onerror = () => {
        es.close()
        eventSourceRef.current = null
        reconnectTimer = setTimeout(connect, 3000)
      }
    }

    connect()

    return () => {
      if (reconnectTimer) clearTimeout(reconnectTimer)
      if (eventSourceRef.current) {
        eventSourceRef.current.close()
        eventSourceRef.current = null
      }
    }
  }, [sessionId, scrollToBottom])

  const handleSend = async () => {
    const content = inputValue.trim()
    if (!content || !sessionId) return
    setSending(true)
    try {
      const result = await post<MessageDTO>(`/sessions/${sessionId}/messages`, { content })
      if (result.success) {
        setInputValue('')
      } else {
        message.error(result.message)
      }
    } catch (error: any) {
      message.error(error.message || '发送消息失败')
    } finally {
      setSending(false)
    }
  }

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: '60vh' }}>
        <Spin size="large" />
      </div>
    )
  }

  const typeItem = session ? typeMap[session.type] : null

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 200px)' }}>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 16, gap: 12 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/sessions')}>返回</Button>
        <h2 style={{ margin: 0 }}>{session?.title || '会话详情'}</h2>
        {typeItem && <Tag color={typeItem.color}>{session?.typeDesc || typeItem.text}</Tag>}
        <Tag>消息数: {session?.messageCount ?? messages.length}</Tag>
      </div>

      <div
        ref={scrollRef}
        style={{
          flex: 1,
          overflowY: 'auto',
          padding: '16px',
          background: '#fff',
          borderRadius: 8,
          border: '1px solid #f0f0f0',
          height: 'calc(100vh - 280px)',
        }}
      >
        {messages.length === 0 ? (
          <div style={{ textAlign: 'center', color: '#999', padding: '40px 0' }}>暂无消息</div>
        ) : (
          messages.map((msg) => (
            <MessageBubble key={msg.messageId} message={msg} isSelf={msg.senderId === currentUserId} />
          ))
        )}
      </div>

      <div style={{ display: 'flex', gap: 8, marginTop: 12, alignItems: 'flex-end' }}>
        <Input.TextArea
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          placeholder="输入消息..."
          autoSize={{ minRows: 1, maxRows: 4 }}
          onPressEnter={(e) => {
            if (!e.shiftKey) {
              e.preventDefault()
              handleSend()
            }
          }}
          style={{ flex: 1 }}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          loading={sending}
          onClick={handleSend}
          disabled={!inputValue.trim()}
        >
          发送
        </Button>
      </div>
    </div>
  )
}