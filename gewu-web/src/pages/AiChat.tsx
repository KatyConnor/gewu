import { useState, useEffect, useCallback, useRef } from 'react'
import { Card, Button, Input, Select, Spin, Avatar, Empty, message as antMessage } from 'antd'
import {
  RobotOutlined,
  UserOutlined,
  SendOutlined,
  StopOutlined,
  PlusOutlined,
  DeleteOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { get, postSSE, ChatMessage, ChatRequest, ModelInfo } from '../services/api'

interface Conversation {
  sessionId: string
  title: string
  messages: ChatMessage[]
  createdAt: number
}

const suggestedPrompts = [
  '帮我用 TypeScript 写一个快速排序',
  '解释一下 React 的 useEffect 依赖数组',
  '总结一下微服务架构的优缺点',
  '写一首关于秋天的诗',
]

let sessionSeq = 0
function genSessionId() {
  sessionSeq += 1
  return `local-${Date.now()}-${sessionSeq}`
}

function renderMarkdownLite(content: string) {
  const blocks: React.ReactNode[] = []
  const segments = content.split(/```/)
  segments.forEach((seg, idx) => {
    if (idx % 2 === 1) {
      const lines = seg.split('\n')
      const firstLine = lines[0]
      const langMatch = firstLine && /^[a-zA-Z0-9+#-]+$/.test(firstLine.trim())
      const code = langMatch ? lines.slice(1).join('\n') : seg
      const lang = langMatch ? firstLine.trim() : ''
      blocks.push(
        <pre
          key={`code-${idx}`}
          style={{
            background: '#1e1e1e',
            color: '#d4d4d4',
            padding: '12px',
            borderRadius: 6,
            overflowX: 'auto',
            fontSize: 13,
            lineHeight: 1.5,
            margin: '8px 0',
          }}
        >
          {lang && (
            <div style={{ color: '#888', fontSize: 11, marginBottom: 6 }}>{lang}</div>
          )}
          <code style={{ fontFamily: 'Menlo, Consolas, monospace', whiteSpace: 'pre' }}>{code}</code>
        </pre>,
      )
    } else if (seg.length > 0) {
      blocks.push(<span key={`text-${idx}`}>{renderInline(seg)}</span>)
    }
  })
  return <>{blocks}</>
}

function renderInline(text: string) {
  const nodes: React.ReactNode[] = []
  const lines = text.split('\n')
  lines.forEach((line, li) => {
    const parts = line.split(/(\*\*[^*]+\*\*)/g)
    parts.forEach((part, pi) => {
      if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
        nodes.push(<strong key={`${li}-${pi}`}>{part.slice(2, -2)}</strong>)
      } else if (part.length > 0) {
        nodes.push(<span key={`${li}-${pi}`}>{part}</span>)
      }
    })
    if (li < lines.length - 1) nodes.push(<br key={`br-${li}`} />)
  })
  return <>{nodes}</>
}

interface MessageBubbleProps {
  msg: ChatMessage
  createdAt: number
  streaming?: boolean
}

function MessageBubble({ msg, createdAt, streaming }: MessageBubbleProps) {
  const isUser = msg.role === 'user'
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: isUser ? 'flex-end' : 'flex-start',
        marginBottom: 12,
      }}
    >
      {!isUser && (
        <Avatar icon={<RobotOutlined />} style={{ marginRight: 8, flexShrink: 0, background: '#722ed1' }} />
      )}
      <div style={{ maxWidth: '70%' }}>
        <div
          style={{
            fontSize: 12,
            color: '#999',
            marginBottom: 4,
            textAlign: isUser ? 'right' : 'left',
          }}
        >
          {isUser ? '我' : 'AI'} · {dayjs(createdAt).format('HH:mm:ss')}
        </div>
        <div
          style={{
            padding: '8px 12px',
            borderRadius: 8,
            background: isUser ? '#1677ff' : '#f5f5f5',
            color: isUser ? '#fff' : '#333',
            wordBreak: 'break-word',
            lineHeight: 1.6,
          }}
        >
          {isUser ? (
            <span style={{ whiteSpace: 'pre-wrap' }}>{msg.content}</span>
          ) : (
            <>
              {renderMarkdownLite(msg.content)}
              {streaming && <TypingDots />}
            </>
          )}
        </div>
      </div>
      {isUser && (
        <Avatar icon={<UserOutlined />} style={{ marginLeft: 8, flexShrink: 0, background: '#1677ff' }} />
      )}
    </div>
  )
}

function TypingDots() {
  return (
    <span style={{ display: 'inline-flex', gap: 4, marginLeft: 6, verticalAlign: 'middle' }}>
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          style={{
            width: 6,
            height: 6,
            borderRadius: '50%',
            background: '#999',
            display: 'inline-block',
            animation: `aidot-blink 1.2s ${i * 0.2}s infinite`,
          }}
        />
      ))}
      <style>{`@keyframes aidot-blink{0%,80%,100%{opacity:0.2}40%{opacity:1}}`}</style>
    </span>
  )
}

export default function AiChat() {
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [currentSessionId, setCurrentSessionId] = useState<string>('')
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [streaming, setStreaming] = useState(false)
  const [models, setModels] = useState<ModelInfo[]>([])
  const [modelProvider, setModelProvider] = useState('qwen')
  const [modelName, setModelName] = useState('qwen-plus')
  const [inputValue, setInputValue] = useState('')
  const [loadingModels, setLoadingModels] = useState(true)

  const scrollRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)
  const abortedRef = useRef(false)

  const currentConversation = conversations.find((c) => c.sessionId === currentSessionId)

  const scrollToBottom = useCallback(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [])

  useEffect(() => {
    scrollToBottom()
  }, [messages, scrollToBottom])

  const fetchModels = useCallback(async () => {
    setLoadingModels(true)
    try {
      const result = await get<ModelInfo[]>('/ai/models')
      if (result.success && result.data) {
        const supported = result.data.filter((m) => m.supported)
        setModels(supported)
        if (supported.length > 0) {
          setModelProvider(supported[0].provider)
          setModelName(supported[0].modelName)
        }
      }
    } catch (error: any) {
      antMessage.error(error.message || '获取模型列表失败')
    } finally {
      setLoadingModels(false)
    }
  }, [])

  useEffect(() => {
    fetchModels()
  }, [fetchModels])

  const persistConversation = useCallback((sessionId: string, msgs: ChatMessage[], title?: string) => {
    setConversations((prev) => {
      const idx = prev.findIndex((c) => c.sessionId === sessionId)
      if (idx === -1) {
        return [
          { sessionId, title: title || '新对话', messages: msgs, createdAt: Date.now() },
          ...prev,
        ]
      }
      const next = [...prev]
      next[idx] = { ...next[idx], messages: msgs, title: title || next[idx].title }
      return next
    })
  }, [])

  const handleNewConversation = () => {
    if (streaming) return
    const id = genSessionId()
    const conv: Conversation = { sessionId: id, title: '新对话', messages: [], createdAt: Date.now() }
    setConversations((prev) => [conv, ...prev])
    setCurrentSessionId(id)
    setMessages([])
  }

  const handleSelectConversation = (sessionId: string) => {
    if (streaming || sessionId === currentSessionId) return
    const conv = conversations.find((c) => c.sessionId === sessionId)
    setCurrentSessionId(sessionId)
    setMessages(conv?.messages || [])
  }

  const handleDeleteConversation = (sessionId: string) => {
    if (streaming) return
    setConversations((prev) => prev.filter((c) => c.sessionId !== sessionId))
    if (sessionId === currentSessionId) {
      setCurrentSessionId('')
      setMessages([])
    }
  }

  const handleStop = () => {
    abortedRef.current = true
    if (abortRef.current) {
      abortRef.current.abort()
      abortRef.current = null
    }
    setStreaming(false)
  }

  const handleSend = async () => {
    const content = inputValue.trim()
    if (!content || streaming) return

    let sessionId = currentSessionId
    if (!sessionId) {
      sessionId = genSessionId()
      setCurrentSessionId(sessionId)
    }

    const userMsg: ChatMessage = { role: 'user', content }
    const assistantMsg: ChatMessage = { role: 'assistant', content: '' }

    let history = [...messages, userMsg]
    setMessages([...history, assistantMsg])
    setInputValue('')
    setStreaming(true)
    abortedRef.current = false

    const controller = new AbortController()
    abortRef.current = controller

    const request: ChatRequest = {
      sessionId,
      modelProvider,
      modelName,
      messages: history,
      stream: true,
    }

    let accumulated = ''
    let firstChunk = true

    try {
      await postSSE(
        '/ai/chat/stream',
        request,
        (chunk: string) => {
          if (abortedRef.current) return
          if (firstChunk) {
            firstChunk = false
            if (chunk.trim() === '[DONE]') return
          }
          if (chunk.trim() === '[DONE]') return
          accumulated += chunk
          setMessages((prev) => {
            const next = [...prev]
            next[next.length - 1] = { role: 'assistant', content: accumulated }
            return next
          })
        },
        (err: string) => {
          if (!abortedRef.current) antMessage.error(err || '流式请求失败')
        },
      )
    } catch (error: any) {
      if (error?.name !== 'AbortError' && !abortedRef.current) {
        antMessage.error(error.message || '请求异常')
      }
    } finally {
      setStreaming(false)
      abortRef.current = null
      const finalMessages = [...history, { role: 'assistant' as const, content: accumulated }]
      const title = history.filter((m) => m.role === 'user')[0]?.content.slice(0, 20) || '新对话'
      persistConversation(sessionId, finalMessages, title)
    }
  }

  const providerOptions = Array.from(new Set(models.map((m) => m.provider)))
  const modelOptions = models.filter((m) => m.provider === modelProvider)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 200px)' }}>
      {/* Header */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          marginBottom: 16,
          gap: 12,
          flexShrink: 0,
        }}
      >
        <h2 style={{ margin: 0, flexShrink: 0 }}>AI 对话</h2>
        <Spin spinning={loadingModels} size="small">
          <Select
            value={modelProvider}
            onChange={(v) => {
              setModelProvider(v)
              const first = models.find((m) => m.provider === v)
              if (first) setModelName(first.modelName)
            }}
            style={{ width: 140 }}
            placeholder="服务商"
            options={providerOptions.map((p) => ({ label: p, value: p }))}
          />
        </Spin>
        <Select
          value={modelName}
          onChange={setModelName}
          style={{ width: 200 }}
          placeholder="模型"
          options={modelOptions.map((m) => ({
            label: m.displayName || m.modelName,
            value: m.modelName,
          }))}
          notFoundContent={loadingModels ? <Spin size="small" /> : null}
        />
        <div style={{ flex: 1 }} />
        <Button icon={<PlusOutlined />} type="primary" onClick={handleNewConversation} disabled={streaming}>
          新建对话
        </Button>
      </div>

      <div style={{ display: 'flex', flex: 1, gap: 16, minHeight: 0 }}>
        {/* Sidebar */}
        <div
          style={{
            width: 240,
            flexShrink: 0,
            background: '#fff',
            borderRadius: 8,
            border: '1px solid #f0f0f0',
            overflowY: 'auto',
            padding: 8,
          }}
        >
          {conversations.length === 0 ? (
            <div style={{ textAlign: 'center', color: '#bbb', padding: '20px 0', fontSize: 13 }}>
              暂无对话历史
            </div>
          ) : (
            conversations.map((conv) => (
              <div
                key={conv.sessionId}
                onClick={() => handleSelectConversation(conv.sessionId)}
                style={{
                  padding: '8px 10px',
                  borderRadius: 6,
                  cursor: streaming ? 'not-allowed' : 'pointer',
                  background: conv.sessionId === currentSessionId ? '#e6f4ff' : 'transparent',
                  border: conv.sessionId === currentSessionId ? '1px solid #91caff' : '1px solid transparent',
                  marginBottom: 4,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                }}
              >
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div
                    style={{
                      fontWeight: conv.sessionId === currentSessionId ? 600 : 400,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                      fontSize: 14,
                    }}
                  >
                    {conv.title}
                  </div>
                  <div style={{ fontSize: 11, color: '#999' }}>
                    {dayjs(conv.createdAt).format('MM-DD HH:mm')}
                  </div>
                </div>
                <DeleteOutlined
                  style={{ color: '#999', flexShrink: 0 }}
                  onClick={(e) => {
                    e.stopPropagation()
                    handleDeleteConversation(conv.sessionId)
                  }}
                />
              </div>
            ))
          )}
        </div>

        {/* Chat area */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
          <div
            ref={scrollRef}
            style={{
              flex: 1,
              overflowY: 'auto',
              padding: 16,
              background: '#fff',
              borderRadius: 8,
              border: '1px solid #f0f0f0',
            }}
          >
            {messages.length === 0 ? (
              <div
                style={{
                  height: '100%',
                  display: 'flex',
                  flexDirection: 'column',
                  justifyContent: 'center',
                  alignItems: 'center',
                }}
              >
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={
                    <span>
                      <RobotOutlined style={{ marginRight: 6, color: '#722ed1' }} />
                      开始与 AI 对话吧
                    </span>
                  }
                />
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, justifyContent: 'center', marginTop: 16, maxWidth: 600 }}>
                  {suggestedPrompts.map((p) => (
                    <Card
                      key={p}
                      size="small"
                      hoverable
                      onClick={() => setInputValue(p)}
                      style={{ cursor: 'pointer', fontSize: 13 }}
                    >
                      {p}
                    </Card>
                  ))}
                </div>
              </div>
            ) : (
              messages.map((msg, idx) => (
                <MessageBubble
                  key={idx}
                  msg={msg}
                  createdAt={currentConversation?.createdAt || Date.now()}
                  streaming={streaming && idx === messages.length - 1 && msg.role === 'assistant'}
                />
              ))
            )}
          </div>

          {/* Input area */}
          <div style={{ display: 'flex', gap: 8, marginTop: 12, alignItems: 'flex-end', flexShrink: 0 }}>
            <Input.TextArea
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              placeholder="输入消息，Enter 发送，Shift+Enter 换行..."
              autoSize={{ minRows: 1, maxRows: 4 }}
              onPressEnter={(e) => {
                if (!e.shiftKey) {
                  e.preventDefault()
                  handleSend()
                }
              }}
              style={{ flex: 1, background: '#fff', borderRadius: 8 }}
              disabled={streaming}
            />
            {streaming ? (
              <Button danger icon={<StopOutlined />} onClick={handleStop}>
                停止
              </Button>
            ) : (
              <Button
                type="primary"
                icon={<SendOutlined />}
                onClick={handleSend}
                disabled={!inputValue.trim()}
              >
                发送
              </Button>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}