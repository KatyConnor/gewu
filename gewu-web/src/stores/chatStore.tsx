import React, { createContext, useContext, useState, useCallback, useRef, type ReactNode } from 'react'

export interface ToolCall {
  name: string
  status: 'pending' | 'running' | 'completed' | 'error'
  input?: Record<string, unknown>
  output?: string
  error?: string
  raw?: string
}

export interface PermissionRequest {
  id: string
  type: string
  description: string
  tool?: string
}

export type ThinkingIntensity = 'none' | 'low' | 'medium' | 'high'

export interface ChatState {
  isStreaming: boolean
  streamingContent: string
  streamingReasoning: string
  streamingMessageID: string | null
  streamingToolCalls: ToolCall[]
  permissionRequest: PermissionRequest | null
  thinkingIntensity: ThinkingIntensity
}

export interface ChatActions {
  sendMessage: (content: string) => Promise<void>
  executeCommand: (command: string) => Promise<void>
  abortStreaming: () => void
  resumeStreaming: () => void
  setThinkingIntensity: (level: ThinkingIntensity) => void
  resolvePermission: (id: string, approved: boolean) => void
}

export type ChatStore = ChatState & ChatActions

const ChatContext = createContext<ChatStore | null>(null)

export function ChatProvider({ children }: { children: ReactNode }) {
  const [isStreaming, setIsStreaming] = useState(false)
  const [streamingContent, setStreamingContent] = useState('')
  const [streamingReasoning, setStreamingReasoning] = useState('')
  const [streamingMessageID, setStreamingMessageID] = useState<string | null>(null)
  const [streamingToolCalls, setStreamingToolCalls] = useState<ToolCall[]>([])
  const [permissionRequest, setPermissionRequest] = useState<PermissionRequest | null>(null)
  const [thinkingIntensity, setThinkingIntensity] = useState<ThinkingIntensity>('medium')
  const abortControllerRef = useRef<AbortController | null>(null)

  const sendMessage = useCallback(async (content: string) => {
    setIsStreaming(true)
    setStreamingContent('')
    setStreamingReasoning('')
    setStreamingToolCalls([])
    abortControllerRef.current = new AbortController()
    try {
      // Real implementation would call the API here
      // For now, this is a placeholder that the actual API integration will replace
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        console.error('Send message failed:', err)
      }
    } finally {
      setIsStreaming(false)
    }
  }, [])

  const executeCommand = useCallback(async (command: string) => {
    await sendMessage(command)
  }, [sendMessage])

  const abortStreaming = useCallback(() => {
    abortControllerRef.current?.abort()
    setIsStreaming(false)
  }, [])

  const resumeStreaming = useCallback(() => {
    // Resume would reconnect to the SSE stream
  }, [])

  const resolvePermission = useCallback((id: string, approved: boolean) => {
    setPermissionRequest(null)
    // Would send resolution to API
  }, [])

  const value: ChatStore = {
    isStreaming,
    streamingContent,
    streamingReasoning,
    streamingMessageID,
    streamingToolCalls,
    permissionRequest,
    thinkingIntensity,
    sendMessage,
    executeCommand,
    abortStreaming,
    resumeStreaming,
    setThinkingIntensity,
    resolvePermission,
  }

  return <ChatContext.Provider value={value}>{children}</ChatContext.Provider>
}

export function useChat(): ChatStore {
  const ctx = useContext(ChatContext)
  if (!ctx) throw new Error('useChat must be used within ChatProvider')
  return ctx
}
