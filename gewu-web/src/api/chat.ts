const API_BASE = '/api'

async function request(url: string, options: RequestInit = {}): Promise<unknown> {
  const res = await fetch(`${API_BASE}${url}`, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options,
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({ error: res.statusText }))
    throw new Error(err.error || '请求失败')
  }
  return res.json()
}

export interface Session {
  id: string
  title: string
  model?: string | { modelID?: string; id?: string; providerID?: string }
  location?: string
  created?: number
  updated?: number
}

export interface Message {
  id?: string
  info?: { id?: string; role?: string }
  role?: string
  content?: string
  parts?: Array<{ type: string; text?: string; [key: string]: unknown }>
  toolCalls?: Array<{
    name: string
    status: string
    output?: string
    error?: string
    callID?: string
    input?: unknown
  }>
  reasoning?: string
  tokens?: { input?: number; output?: number }
  cost?: number
  error?: { message?: string } | string
  streaming?: boolean
  images?: Array<{ data: string; mimeType: string; name?: string }>
  time?: { created?: number; updated?: number }
}

export interface Agent {
  name: string
  description?: string
}

export interface Provider {
  id: string
  name?: string
  source?: string
  models?: Array<{ id: string; name?: string; status?: string; cost?: { input?: number } }>
}

export const sessionApi = {
  list: () => request('/sessions') as Promise<Session[]>,
  get: (id: string) => request(`/sessions/${id}`) as Promise<Session>,
  create: (title?: string) =>
    request('/sessions', { method: 'POST', body: JSON.stringify({ title }) }) as Promise<Session>,
  delete: (id: string) => request(`/sessions/${id}`, { method: 'DELETE' }),
  update: (id: string, title: string) =>
    request(`/sessions/${id}`, { method: 'PATCH', body: JSON.stringify({ title }) }),
  status: () => request('/sessions/status') as Promise<Record<string, { type: string }>>,
  children: (id: string) => request(`/sessions/${id}/children`),
  todo: (id: string) => request(`/sessions/${id}/todo`),
  abort: (id: string) => request(`/sessions/${id}/abort`, { method: 'POST' }),
  share: (id: string) => request(`/sessions/${id}/share`, { method: 'POST' }),
  unshare: (id: string) => request(`/sessions/${id}/share`, { method: 'DELETE' }),
  diff: (id: string, messageID?: string) => {
    const q = messageID ? `?messageID=${messageID}` : ''
    return request(`/sessions/${id}/diff${q}`)
  },
  fork: (id: string, messageID?: string) =>
    request(`/sessions/${id}/fork`, {
      method: 'POST',
      body: JSON.stringify({ messageID }),
    }),
  summarize: (id: string, providerID?: string, modelID?: string) =>
    request(`/sessions/${id}/summarize`, {
      method: 'POST',
      body: JSON.stringify({ providerID, modelID }),
    }),
}

export const messageApi = {
  list: (sessionId: string, limit?: number) => {
    const q = limit ? `?limit=${limit}` : ''
    return request(`/sessions/${sessionId}/messages${q}`) as Promise<Message[]>
  },
  get: (sessionId: string, messageId: string) =>
    request(`/sessions/${sessionId}/messages/${messageId}`) as Promise<Message>,
  send: (sessionId: string, content: string, agent?: string, providerID?: string, modelID?: string) =>
    request(`/sessions/${sessionId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ content, agent, providerID, modelID }),
    }),
  command: (sessionId: string, command: string, args: string, agent?: string, model?: string) =>
    request(`/sessions/${sessionId}/command`, {
      method: 'POST',
      body: JSON.stringify({ command, arguments: args, agent, model }),
    }),
  shell: (sessionId: string, agent?: string, model?: string, command?: string) =>
    request(`/sessions/${sessionId}/shell`, {
      method: 'POST',
      body: JSON.stringify({ agent, model, command }),
    }),
  revert: (sessionId: string, messageID: string, partID?: string) =>
    request(`/sessions/${sessionId}/revert`, {
      method: 'POST',
      body: JSON.stringify({ messageID, partID }),
    }),
  unrevert: (sessionId: string) =>
    request(`/sessions/${sessionId}/unrevert`, { method: 'POST' }),
}

export function chatStream(
  sessionId: string,
  content: string,
  agent?: string,
  providerID?: string,
  modelID?: string,
  thinkingIntensity?: string,
  images?: Array<{ data: string; mimeType: string; name?: string }>
): Promise<Response> {
  const body: Record<string, unknown> = { content, agent, providerID, modelID, thinkingIntensity }
  if (images && images.length > 0) {
    body.images = images
  }
  return fetch(`${API_BASE}/sessions/${sessionId}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export function subscribeSessionEvents(sessionId: string): EventSource {
  return new EventSource(`${API_BASE}/sessions/${sessionId}/events`)
}

export function subscribeGlobalEvents(): EventSource {
  return new EventSource(`${API_BASE}/events`)
}

export const agentApi = {
  list: () => request('/agents') as Promise<Agent[]>,
}

export const permissionApi = {
  respond: (sessionId: string, permissionId: string, response: string, remember?: boolean) =>
    request(`/sessions/${sessionId}/permissions/${permissionId}`, {
      method: 'POST',
      body: JSON.stringify({ response, remember }),
    }),
}

export const fsApi = {
  list: (path?: string) =>
    request(`/fs/list?path=${encodeURIComponent(path || '.')}`) as Promise<{
      entries: Array<{ name: string; type: string; children?: unknown[] }>
    }>,
  complete: (path: string) => request(`/fs/complete?path=${encodeURIComponent(path)}`),
  read: (path: string) => request(`/fs/read?path=${encodeURIComponent(path)}`),
  write: (path: string, content: string) =>
    request('/fs/write', {
      method: 'POST',
      body: JSON.stringify({ path, content }),
    }),
}

export const configApi = {
  get: () => request('/config'),
  update: (config: unknown) =>
    request('/config', { method: 'PATCH', body: JSON.stringify(config) }),
  providers: () => request('/config/providers') as Promise<Provider[]>,
}

export const providerApi = {
  list: () => request('/providers') as Promise<Provider[]>,
  auth: () => request('/providers/auth'),
}

export const projectApi = {
  getRecent: () => request('/projects/recent'),
  save: (project: unknown) =>
    request('/projects', { method: 'POST', body: JSON.stringify(project) }),
  delete: (path: string) =>
    request(`/projects/${encodeURIComponent(path)}`, { method: 'DELETE' }),
  open: (path: string) => request(`/projects/open?path=${encodeURIComponent(path)}`),
}
