import axios from 'axios'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('accessToken')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error),
)

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('accessToken')
      localStorage.removeItem('refreshToken')
      window.location.href = '/login'
    }
    return Promise.reject(error.response?.data || error.message)
  },
)

export interface Result<T> {
  code: number
  message: string
  data: T
  timestamp: number
  success: boolean
}

export interface TokenDTO {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  userId: string
  username: string
  displayName: string
  roles: string[]
}

export interface UserDTO {
  userId: string
  username: string
  email: string
  phone?: string
  displayName: string
  avatarUrl?: string
  status: number
  lastLoginAt?: number
  roleCodes: string[]
}

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  email: string
  password: string
  displayName: string
  phone?: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
  pages?: number
}

export interface ProjectDTO {
  projectId: string
  projectName: string
  description?: string
  visibility: number
  status: number
  ownerId: string
  ownerName?: string
  memberCount?: number
  createdAt: number
  techStack?: string
  worktree?: string
  iconUrl?: string
  iconColor?: string
}

export interface CreateProjectRequest {
  projectName: string
  description?: string
  visibility?: number
  techStack?: string
  worktree?: string
  vcs?: string
  iconUrl?: string
  iconColor?: string
}

export interface SessionDTO {
  sessionId: string
  title: string
  type: number
  typeDesc?: string
  projectId?: string
  status: number
  statusDesc?: string
  isPublic: number
  messageCount: number
  lastMessageAt?: number
  agent?: string
  directory?: string
  createdAt: number
  createdBy?: string
}

export interface CreateSessionRequest {
  title: string
  type: number
  projectId?: string
  isPublic?: number
  agent?: string
  directory?: string
}

export interface MessageDTO {
  messageId: string
  sessionId: string
  senderId: string
  senderName?: string
  messageType: string
  content: string
  replyTo?: string
  seq?: number
  edited?: number
  createdAt: number
}

export interface SendMessageRequest {
  messageType?: string
  content: string
  replyTo?: string
  mentionUserIds?: string
}

export interface AgentDTO {
  agentId: string
  agentName: string
  description?: string
  modelProvider: string
  modelName: string
  modelConfig?: string
  systemPrompt?: string
  status: number
  statusDesc?: string
  version?: number
  createdAt: number
}

export interface CreateAgentRequest {
  agentName: string
  description?: string
  modelProvider: string
  modelName: string
  modelConfig?: string
  systemPrompt?: string
}

export async function post<T>(url: string, data?: unknown): Promise<Result<T>> {
  const response = await api.post<Result<T>>(url, data)
  return response.data
}

export async function get<T>(url: string): Promise<Result<T>> {
  const response = await api.get<Result<T>>(url)
  return response.data
}

export async function put<T>(url: string, data?: unknown): Promise<Result<T>> {
  const response = await api.put<Result<T>>(url, data)
  return response.data
}

export async function del<T>(url: string): Promise<Result<T>> {
  const response = await api.delete<Result<T>>(url)
  return response.data
}

export interface WorkflowDTO {
  workflowId: string
  workflowName: string
  description?: string
  version: number
  status: number
  statusDesc?: string
  category?: string
  createdAt: number
  createdBy?: string
  nodeCount?: number
}

export interface WorkflowNodeDTO {
  nodeId?: string
  workflowId?: string
  nodeName: string
  nodeType: string
  config?: string
  positionX?: number
  positionY?: number
  sortOrder?: number
}

export interface WorkflowTransitionDTO {
  transitionId?: string
  workflowId?: string
  fromNodeId: string
  toNodeId: string
  conditionExpr?: string
  label?: string
  sortOrder?: number
}

export interface SaveWorkflowGraphCommand {
  nodes: WorkflowNodeDTO[]
  transitions: WorkflowTransitionDTO[]
}

export interface CreateWorkflowRequest {
  workflowName: string
  description?: string
  category?: string
}

export interface WorkflowGraphDTO {
  workflowId: string
  nodes: WorkflowNodeDTO[]
  transitions: WorkflowTransitionDTO[]
}

export interface WorkflowInstanceDTO {
  instanceId: string
  workflowId: string
  workflowVersion: number
  title?: string
  status: string
  statusDesc?: string
  initiatorId: string
  initiatorName?: string
  currentNodeId?: string
  currentNodeName?: string
  variables?: string
  startedAt: number
  completedAt?: number
  createdAt: number
}

export interface WorkflowNodeInstanceDTO {
  nodeInstanceId: string
  instanceId: string
  nodeId: string
  nodeName: string
  nodeType: string
  status: string
  statusDesc?: string
  assigneeId?: string
  assigneeName?: string
  input?: string
  output?: string
  startedAt?: number
  completedAt?: number
  remark?: string
}

export interface CompleteNodeRequest {
  output?: string
  remark?: string
  approved?: boolean
}

export interface StartInstanceRequest {
  title?: string
  variables?: string
}

export interface WorkflowNotificationDTO {
  notificationId: string
  instanceId: string
  nodeInstanceId?: string
  nodeId?: string
  nodeName?: string
  receiverId: string
  senderId?: string
  senderName?: string
  content?: string
  isRead: number
  sentAt: number
}

export interface AgentToolDTO {
  toolId: string
  agentId: string
  toolName: string
  description?: string
  toolType: string
  endpoint?: string
  timeoutMs?: number
  status: number
  sortOrder?: number
}

export interface CreateToolRequest {
  agentId: string
  toolName: string
  description?: string
  toolType: string
  endpoint?: string
  timeoutMs?: number
  sortOrder?: number
}

export interface AgentExecutionDTO {
  executionId: string
  agentId: string
  sessionId?: string
  userId: string
  status: string
  input?: string
  output?: string
  errorMessage?: string
  tokensUsed?: number
  startedAt?: number
  completedAt?: number
  durationMs?: number
}

export interface UpdateAgentRequest {
  agentName?: string
  description?: string
  modelProvider?: string
  modelName?: string
  modelConfig?: string
  systemPrompt?: string
  status?: number
}

export interface ChatMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
}

export interface ChatRequest {
  sessionId?: string
  modelProvider?: string
  modelName?: string
  messages: ChatMessage[]
  stream?: boolean
  temperature?: number
  maxTokens?: number
}

export interface ChatResponse {
  content: string
  role: string
  model: string
  usage?: { promptTokens: number; completionTokens: number; totalTokens: number }
}

export interface ModelInfo {
  provider: string
  modelName: string
  displayName: string
  maxTokens: number
  supported: boolean
}

export interface SandboxDTO {
  sandboxId: string
  sandboxName: string
  image: string
  status: string
  statusDesc?: string
  cpuCores: number
  memoryMb: number
  diskMb: number
  createdAt: number
  startedAt?: number
  stoppedAt?: number
  ip?: string
  ports?: string
}

export interface CreateSandboxRequest {
  sandboxName: string
  image: string
  cpuCores?: number
  memoryMb?: number
  diskMb?: number
  env?: string
  cmd?: string
}

export interface SandboxLogDTO {
  logId: string
  sandboxId: string
  action: string
  status: string
  detail?: string
  operatorId?: string
  operatorName?: string
  createdAt: number
}

export async function postSSE(url: string, data: unknown, onMessage: (chunk: string) => void, onError?: (err: string) => void): Promise<void> {
  const token = localStorage.getItem('accessToken')
  const response = await fetch(`/api/v1${url}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(data),
  })

  if (!response.ok) {
    if (onError) onError(`HTTP ${response.status}`)
    return
  }

  const reader = response.body?.getReader()
  if (!reader) return

  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    for (const line of lines) {
      if (line.startsWith('data: ')) {
        onMessage(line.slice(6))
      }
    }
  }
  if (buffer.startsWith('data: ')) {
    onMessage(buffer.slice(6))
  }
}

export default api
