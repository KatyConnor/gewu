import React, { createContext, useContext, useReducer, useCallback, useEffect, useMemo, type ReactNode } from 'react'
import { themes, getThemeById, getThemeVars, applyThemeVars, defaultThemeId, type Theme, type ThemeVars } from '../styles/themes'

export interface Session {
  id: string
  title?: string
  agent?: string
  model?: unknown
  time?: { created?: number; updated?: number }
  parentID?: string
  summary?: string
  cost?: number
  share?: boolean
}

export interface Message {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  reasoning?: string
  parts?: unknown[]
  toolCalls?: { name: string; status: string; input?: unknown; output?: string; error?: string; raw?: string }[]
  time?: { created?: number; updated?: number }
  agent?: string
  model?: unknown
  providerID?: string
  modelID?: string
  system?: string | null
  error?: string
  cost?: number
  tokens?: {
    input?: number
    output?: number
    reasoning?: number
    cache?: { read?: number; write?: number }
  }
  summary?: string
}

export interface Agent {
  id: string
  name?: string
  [key: string]: unknown
}

export interface Provider {
  id: string
  name?: string
  models?: unknown
  [key: string]: unknown
}

export interface Config {
  [key: string]: unknown
}

export interface FileEntry {
  name: string
  path: string
  type: 'file' | 'directory'
  children?: FileEntry[]
}

export interface OpenFile {
  path: string
  name: string
  modified: boolean
}

export interface Project {
  name: string
  path: string
  lastOpened?: number
}

export interface Notification {
  id: number
  msg: string
  type: 'info' | 'success' | 'error' | 'warning'
}

export interface ReviewIssue {
  file_path?: string
  [key: string]: unknown
}

export interface ReviewResult {
  task_id?: string
  status: 'queued' | 'running' | 'completed' | 'failed'
  issues?: ReviewIssue[]
  error?: string
  elapsed_seconds?: number
}

export interface AppState {
  sessions: Session[]
  currentSessionId: string | null
  currentSession: Session | null
  messages: Message[]
  loading: boolean
  streaming: boolean
  streamingContent: string
  error: string | null
  agents: Agent[]
  selectedAgent: string
  selectedModel: string | null
  providers: Provider[]
  config: Config | null
  connected: boolean
  theme: 'dark' | 'light'
  themeId: string
  sidePanelTab: 'project' | 'context' | 'todo'
  showSidePanel: boolean
  showTerminal: boolean
  showSettings: boolean
  showCommandPalette: boolean
  commandPaletteQuery: string
  projects: Project[]
  currentProject: Project | null
  activeRailIcon: string | null
  showSessionPanel: boolean
  showReviewPanel: boolean
  showTestPanel: boolean
  showProjectDialog: boolean
  recentProjects: Project[]
  reviewResult: ReviewResult | null
  reviewRunning: boolean
  reviewHistory: ReviewResult[]
  searchQuery: string
  fileTree: FileEntry[]
  openFiles: OpenFile[]
  activeFile: string | null
  terminalTabs: { id: number; title: string }[]
  activeTerminal: number
  notifications: Notification[]
  sessionStatus: Record<string, { type?: string }>
  showReasoning: boolean
  expandShellTools: boolean
  expandEditTools: boolean
  showProgress: boolean
  notifAgent: boolean
  notifPermissions: boolean
  notifErrors: boolean
  autoAcceptPermissions: boolean
  shell: string
  showFileTree: boolean
  showSearch: boolean
  showStatus: boolean
  showCustomAgents: boolean
  releaseNotes: boolean
  serverUrl: string
  servers: { id: string; name: string; url: string; default?: boolean }[]
  showSidebarPanel: boolean
}

type Action =
  | { type: 'SET'; key: keyof AppState; value: unknown }
  | { type: 'SET_MULTIPLE'; payload: Partial<AppState> }
  | { type: 'LOAD_SESSIONS'; sessions: Session[] }
  | { type: 'SELECT_SESSION'; id: string; session: Session | null }
  | { type: 'SET_MESSAGES'; messages: Message[] }
  | { type: 'CLEAR_CURRENT' }
  | { type: 'ADD_NOTIFICATION'; notification: Notification }
  | { type: 'REMOVE_NOTIFICATION'; id: number }
  | { type: 'SET_THEME'; themeId: string; theme: 'dark' | 'light' }
  | { type: 'TOGGLE_THEME' }
  | { type: 'TOGGLE_SIDEBAR_PANEL' }
  | { type: 'TOGGLE_SESSION_PANEL' }
  | { type: 'TOGGLE_REVIEW_PANEL' }
  | { type: 'TOGGLE_TEST_PANEL' }
  | { type: 'CLOSE_ALL_PANELS' }
  | { type: 'OPEN_FILE'; file: OpenFile }
  | { type: 'CLOSE_FILE'; path: string }

function loadSavedSettings(): Partial<AppState> {
  try {
    const saved = JSON.parse(localStorage.getItem('gewu-settings') || '{}')
    const result: Partial<AppState> = {}
    const keys: (keyof AppState)[] = [
      'showReasoning', 'expandShellTools', 'expandEditTools', 'showProgress',
      'notifAgent', 'notifPermissions', 'notifErrors',
      'autoAcceptPermissions', 'shell', 'showFileTree', 'showSearch',
      'showStatus', 'showCustomAgents', 'releaseNotes', 'serverUrl',
      'recentProjects', 'currentProject',
    ]
    for (const key of keys) {
      if (saved[key] !== undefined) (result as Record<string, unknown>)[key] = saved[key]
    }
    if (saved.servers) result.servers = saved.servers
    return result
  } catch {
    return {}
  }
}

const defaultTerminalTabs = [{ id: 1, title: 'Terminal 1' }]
const defaultServers = [{ id: 'default', name: '本地服务器', url: 'http://127.0.0.1:4096', default: true }]

const initialState: AppState = {
  sessions: [],
  currentSessionId: null,
  currentSession: null,
  messages: [],
  loading: false,
  streaming: false,
  streamingContent: '',
  error: null,
  agents: [],
  selectedAgent: 'build',
  selectedModel: null,
  providers: [],
  config: null,
  connected: false,
  theme: 'dark',
  themeId: defaultThemeId,
  sidePanelTab: 'project',
  showSidePanel: false,
  showTerminal: false,
  showSettings: false,
  showCommandPalette: false,
  commandPaletteQuery: '',
  projects: [],
  currentProject: null,
  activeRailIcon: null,
  showSessionPanel: false,
  showReviewPanel: false,
  showTestPanel: false,
  showProjectDialog: false,
  recentProjects: [],
  reviewResult: null,
  reviewRunning: false,
  reviewHistory: [],
  searchQuery: '',
  fileTree: [],
  openFiles: [],
  activeFile: null,
  terminalTabs: defaultTerminalTabs,
  activeTerminal: 1,
  notifications: [],
  sessionStatus: {},
  showReasoning: true,
  expandShellTools: true,
  expandEditTools: true,
  showProgress: true,
  notifAgent: true,
  notifPermissions: true,
  notifErrors: false,
  autoAcceptPermissions: false,
  shell: '',
  showFileTree: false,
  showSearch: false,
  showStatus: false,
  showCustomAgents: false,
  releaseNotes: true,
  serverUrl: 'http://127.0.0.1:4096',
  servers: defaultServers,
  showSidebarPanel: true,
}

function reducer(state: AppState, action: Action): AppState {
  switch (action.type) {
    case 'SET':
      return { ...state, [action.key]: action.value }
    case 'SET_MULTIPLE':
      return { ...state, ...action.payload }
    case 'LOAD_SESSIONS':
      return { ...state, sessions: action.sessions, loading: false }
    case 'SELECT_SESSION':
      return { ...state, currentSessionId: action.id, currentSession: action.session }
    case 'SET_MESSAGES':
      return { ...state, messages: action.messages }
    case 'CLEAR_CURRENT':
      return { ...state, currentSessionId: null, currentSession: null, messages: [] }
    case 'ADD_NOTIFICATION':
      return { ...state, notifications: [...state.notifications, action.notification] }
    case 'REMOVE_NOTIFICATION':
      return { ...state, notifications: state.notifications.filter(n => n.id !== action.id) }
    case 'SET_THEME':
      return { ...state, themeId: action.themeId, theme: action.theme }
    case 'TOGGLE_THEME': {
      const newMode = state.theme === 'dark' ? 'light' : 'dark'
      return { ...state, theme: newMode }
    }
    case 'TOGGLE_SIDEBAR_PANEL':
      return { ...state, showSidebarPanel: !state.showSidebarPanel }
    case 'TOGGLE_SESSION_PANEL':
      return {
        ...state,
        showSessionPanel: !state.showSessionPanel,
        showReviewPanel: false,
        showTestPanel: false,
        activeRailIcon: !state.showSessionPanel ? 'sessions' : null,
      }
    case 'TOGGLE_REVIEW_PANEL':
      return {
        ...state,
        showReviewPanel: !state.showReviewPanel,
        showSessionPanel: false,
        showTestPanel: false,
        activeRailIcon: !state.showReviewPanel ? 'review' : null,
      }
    case 'TOGGLE_TEST_PANEL':
      return {
        ...state,
        showTestPanel: !state.showTestPanel,
        showSessionPanel: false,
        showReviewPanel: false,
        activeRailIcon: !state.showTestPanel ? 'test' : null,
      }
    case 'CLOSE_ALL_PANELS':
      return {
        ...state,
        showSessionPanel: false,
        showReviewPanel: false,
        showTestPanel: false,
        showProjectDialog: false,
        activeRailIcon: null,
      }
    case 'OPEN_FILE': {
      const exists = state.openFiles.find(f => f.path === action.file.path)
      if (exists) return { ...state, activeFile: action.file.path }
      return {
        ...state,
        openFiles: [...state.openFiles, action.file],
        activeFile: action.file.path,
      }
    }
    case 'CLOSE_FILE': {
      const idx = state.openFiles.findIndex(f => f.path === action.path)
      if (idx === -1) return state
      const newFiles = state.openFiles.filter(f => f.path !== action.path)
      let newActive = state.activeFile
      if (state.activeFile === action.path) {
        const newIdx = Math.min(idx, newFiles.length - 1)
        newActive = newFiles[newIdx]?.path || null
      }
      return { ...state, openFiles: newFiles, activeFile: newActive }
    }
    default:
      return state
  }
}

export interface AppStore extends AppState {
  allThemes: Theme[]
  currentTheme: Theme
  filteredSessions: Session[]
  groupedSessions: { today: Session[]; yesterday: Session[]; older: Session[] }
  persistSettings: () => void
  initTheme: () => void
  setTheme: (id: string, mode?: 'dark' | 'light') => void
  toggleTheme: () => void
  loadSessions: () => Promise<void>
  selectSession: (id: string) => Promise<void>
  loadMessages: (sessionId: string, limit?: number) => Promise<void>
  createSession: (title?: string) => Promise<Session | null>
  deleteSession: (id: string) => Promise<void>
  updateSessionTitle: (id: string, title: string) => Promise<void>
  loadAgents: () => Promise<void>
  loadProviders: () => Promise<void>
  loadConfig: () => Promise<void>
  checkHealth: () => Promise<void>
  addNotification: (msg: string, type?: Notification['type']) => void
  toggleSidebarPanel: () => void
  toggleSessionPanel: () => void
  toggleReviewPanel: () => void
  toggleTestPanel: () => void
  closeAllPanels: () => void
  clearCurrent: () => void
  openProject: (project: Project) => void
  closeProject: () => void
  loadFileTree: () => Promise<void>
  openFile: (path: string) => void
  closeFile: (path: string) => void
  set: <K extends keyof AppState>(key: K, value: AppState[K]) => void
}

const AppContext = createContext<AppStore | null>(null)

function normalizeSession(raw: unknown): Session | null {
  if (!raw) return null
  const obj = raw as Record<string, unknown>
  const info = (obj.info || obj) as Record<string, unknown>
  return {
    id: (info.id || obj.id) as string,
    title: (info.title || obj.title) as string,
    agent: (info.agent || obj.agent) as string,
    model: info.model || obj.model,
    time: (info.time || obj.time || { created: obj.createdAt, updated: obj.updatedAt }) as Session['time'],
    parentID: (info.parentID || obj.parentID) as string,
    summary: (info.summary || obj.summary) as string,
  }
}

function normalizeMessages(raw: unknown): Message[] {
  if (!raw) return []
  let arr: unknown[] = []
  const obj = raw as Record<string, unknown>
  if (obj.data && Array.isArray(obj.data)) arr = obj.data
  else if (Array.isArray(raw)) arr = raw
  else return []

  return arr.map((item: unknown) => {
    const it = item as Record<string, unknown>
    if (it.info && it.parts) {
      const info = it.info as Record<string, unknown>
      const parts = (it.parts || []) as { type: string; text?: string; tool?: string; name?: string; summary?: string; state?: Record<string, unknown> }[]
      const textParts = parts.filter(p => p.type === 'text')
      const toolParts = parts.filter(p => p.type === 'tool')
      const reasoningParts = parts.filter(p => p.type === 'reasoning')
      return {
        id: info.id as string,
        role: info.role as Message['role'],
        content: textParts.map(p => p.text || '').join('\n'),
        reasoning: reasoningParts.map(p => p.text || p.summary || '').join('\n'),
        parts,
        toolCalls: toolParts.map(p => ({
          name: p.tool || '',
          status: ((p.state?.status as string) || 'pending') as Message['toolCalls'] extends (infer T)[] ? T extends { status: infer S } ? S : never : never,
          input: p.state?.input as Record<string, unknown> | undefined,
          output: p.state?.output as string | undefined,
          error: p.state?.error as string | undefined,
          raw: p.state?.raw as string | undefined,
        })),
        time: info.time as Message['time'],
        agent: info.agent as string,
        model: info.model,
        providerID: info.providerID as string,
        modelID: info.modelID as string,
        system: (info.system as string) || null,
        error: info.error as string,
        cost: info.cost as number,
        tokens: info.tokens as Message['tokens'],
        summary: info.summary as string,
      } as Message
    }
    if (it.role === 'user' || it.type === 'user') {
      return {
        id: it.id as string,
        role: 'user' as const,
        content: (it.text as string) || '',
        parts: it.parts || [],
        toolCalls: [],
        time: it.time as Message['time'],
        agent: it.agent as string,
        model: it.model,
        system: (it.system as string) || null,
      } as Message
    }
    if (it.role === 'assistant' || it.type === 'assistant') {
      const contentArr = (it.content || it.parts || []) as { type: string; text?: string; name?: string; tool?: string; state?: Record<string, unknown> }[]
      const textParts = contentArr.filter(p => p.type === 'text')
      const toolParts = contentArr.filter(p => p.type === 'tool')
      const reasoningParts = contentArr.filter(p => p.type === 'reasoning')
      return {
        id: it.id as string,
        role: 'assistant' as const,
        content: textParts.map(p => p.text || '').join('\n'),
        reasoning: reasoningParts.map(p => p.text || p.summary || '').join('\n'),
        parts: contentArr,
        toolCalls: toolParts.map(p => ({
          name: (p.name || p.tool) || '',
          status: ((p.state?.status as string) || 'pending') as Message['toolCalls'] extends (infer T)[] ? T extends { status: infer S } ? S : never : never,
          input: p.state?.input as Record<string, unknown> | undefined,
          output: p.state?.output as string | undefined,
          error: p.state?.error as string | undefined,
          raw: p.state?.raw as string | undefined,
        })),
        time: it.time as Message['time'],
        agent: it.agent as string,
        model: it.model,
        providerID: it.providerID as string,
        modelID: it.modelID as string,
        error: it.error as string,
        cost: it.cost as number,
        tokens: it.tokens as Message['tokens'],
      } as Message
    }
    return {
      id: (it.id as string) || String(Math.random()),
      role: (it.role as Message['role']) || 'system',
      content: (it.content as string) || (it.text as string) || '',
      parts: it.parts || [],
      toolCalls: [],
    } as Message
  })
}

export function AppProvider({ children }: { children: ReactNode }) {
  const savedSettings = useMemo(() => loadSavedSettings(), [])
  const [state, dispatch] = useReducer(reducer, { ...initialState, ...savedSettings })

  const persistSettings = useCallback(() => {
    try {
      const toSave: Record<string, unknown> = {}
      const keys: (keyof AppState)[] = [
        'showReasoning', 'expandShellTools', 'expandEditTools', 'showProgress',
        'notifAgent', 'notifPermissions', 'notifErrors',
        'autoAcceptPermissions', 'shell', 'showFileTree', 'showSearch',
        'showStatus', 'showCustomAgents', 'releaseNotes', 'serverUrl',
        'servers', 'recentProjects', 'currentProject',
      ]
      for (const key of keys) {
        toSave[key] = state[key]
      }
      localStorage.setItem('gewu-settings', JSON.stringify(toSave))
    } catch { /* ignore */ }
  }, [state])

  const allThemes = useMemo(() => themes, [])
  const currentTheme = useMemo(() => getThemeById(state.themeId), [state.themeId])

  const filteredSessions = useMemo(() => {
    if (!state.searchQuery) return state.sessions
    const q = state.searchQuery.toLowerCase()
    return state.sessions.filter(s => (s.title || '').toLowerCase().includes(q))
  }, [state.sessions, state.searchQuery])

  const groupedSessions = useMemo(() => {
    const now = Date.now()
    const today: Session[] = []
    const yesterday: Session[] = []
    const older: Session[] = []
    for (const s of filteredSessions) {
      const ts = s.time?.updated || s.time?.created
      const d = new Date(ts || 0).getTime()
      const diff = now - d
      if (diff < 86400000) today.push(s)
      else if (diff < 172800000) yesterday.push(s)
      else older.push(s)
    }
    return { today, yesterday, older }
  }, [filteredSessions])

  const setTheme = useCallback((id: string, mode?: 'dark' | 'light') => {
    const targetMode = mode || state.theme
    const vars = getThemeVars(id, targetMode)
    if (vars) applyThemeVars(vars)
    dispatch({ type: 'SET_THEME', themeId: id, theme: targetMode })
    document.documentElement.setAttribute('data-theme', targetMode)
    document.documentElement.setAttribute('data-theme-id', id)
    try {
      localStorage.setItem('gewu-theme-id', id)
      localStorage.setItem('gewu-theme-mode', targetMode)
    } catch { /* ignore */ }
  }, [state.theme])

  const toggleTheme = useCallback(() => {
    const newMode = state.theme === 'dark' ? 'light' : 'dark'
    setTheme(state.themeId, newMode)
  }, [state.theme, state.themeId, setTheme])

  const initTheme = useCallback(() => {
    try {
      const savedId = localStorage.getItem('gewu-theme-id')
      const savedMode = localStorage.getItem('gewu-theme-mode') as 'dark' | 'light' | null
      if (savedId && getThemeById(savedId)) {
        setTheme(savedId, savedMode || 'dark')
      } else {
        setTheme(defaultThemeId, 'dark')
      }
    } catch {
      setTheme(defaultThemeId, 'dark')
    }
  }, [setTheme])

  const loadSessions = useCallback(async () => {
    dispatch({ type: 'SET', key: 'loading', value: true })
    try {
      const result = await fetch('/api/session').then(r => r.json())
      const sessions = Array.isArray(result) ? result : (result?.data && Array.isArray(result.data) ? result.data : [])
      dispatch({ type: 'LOAD_SESSIONS', sessions })
    } catch (e) {
      dispatch({ type: 'SET_MULTIPLE', payload: { error: (e as Error).message, sessions: [], loading: false } })
    }
  }, [])

  const selectSession = useCallback(async (id: string) => {
    try {
      const raw = await fetch(`/api/session/${id}`).then(r => r.json())
      const session = normalizeSession(raw)
      dispatch({ type: 'SELECT_SESSION', id, session })
      await loadMessages(id)
    } catch (e) {
      dispatch({ type: 'SET', key: 'error', value: (e as Error).message })
    }
  }, [])

  const loadMessages = useCallback(async (sessionId: string, limit = 100) => {
    try {
      const raw = await fetch(`/api/session/${sessionId}/messages?limit=${limit}`).then(r => r.json())
      const messages = normalizeMessages(raw)
      dispatch({ type: 'SET_MESSAGES', messages })
    } catch (e) {
      dispatch({ type: 'SET', key: 'error', value: (e as Error).message })
    }
  }, [])

  const createSession = useCallback(async (title?: string) => {
    try {
      const session = await fetch('/api/session', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: title || '新会话' }),
      }).then(r => r.json())
      await loadSessions()
      if (session?.id) await selectSession(session.id)
      return session
    } catch {
      return null
    }
  }, [loadSessions, selectSession])

  const deleteSession = useCallback(async (id: string) => {
    await fetch(`/api/session/${id}`, { method: 'DELETE' })
    if (state.currentSessionId === id) {
      dispatch({ type: 'CLEAR_CURRENT' })
    }
    await loadSessions()
  }, [state.currentSessionId, loadSessions])

  const updateSessionTitle = useCallback(async (id: string, title: string) => {
    await fetch(`/api/session/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title }),
    })
    await loadSessions()
    if (state.currentSessionId === id) await selectSession(id)
  }, [state.currentSessionId, loadSessions, selectSession])

  const loadAgents = useCallback(async () => {
    try {
      const result = await fetch('/api/agent').then(r => r.json())
      const agents = Array.isArray(result) ? result : (result?.data && Array.isArray(result.data) ? result.data : [])
      dispatch({ type: 'SET', key: 'agents', value: agents })
    } catch {
      dispatch({ type: 'SET', key: 'agents', value: [] })
    }
  }, [])

  const loadProviders = useCallback(async () => {
    try {
      const result = await fetch('/api/provider').then(r => r.json())
      if (Array.isArray(result)) {
        dispatch({ type: 'SET', key: 'providers', value: result })
      } else if (result?.all && Array.isArray(result.all)) {
        dispatch({ type: 'SET', key: 'providers', value: result.all })
      } else {
        dispatch({ type: 'SET', key: 'providers', value: [] })
      }
    } catch {
      dispatch({ type: 'SET', key: 'providers', value: [] })
    }
  }, [])

  const loadConfig = useCallback(async () => {
    try {
      const result = await fetch('/api/config').then(r => r.json())
      dispatch({ type: 'SET', key: 'config', value: result || null })
    } catch {
      dispatch({ type: 'SET', key: 'config', value: null })
    }
  }, [])

  const checkHealth = useCallback(async () => {
    try {
      await fetch('/api/health')
      dispatch({ type: 'SET', key: 'connected', value: true })
    } catch {
      dispatch({ type: 'SET', key: 'connected', value: false })
    }
  }, [])

  const addNotification = useCallback((msg: string, type: Notification['type'] = 'info') => {
    const id = Date.now()
    dispatch({ type: 'ADD_NOTIFICATION', notification: { id, msg, type } })
    setTimeout(() => {
      dispatch({ type: 'REMOVE_NOTIFICATION', id })
    }, 5000)
  }, [])

  const toggleSidebarPanel = useCallback(() => dispatch({ type: 'TOGGLE_SIDEBAR_PANEL' }), [])
  const toggleSessionPanel = useCallback(() => dispatch({ type: 'TOGGLE_SESSION_PANEL' }), [])
  const toggleReviewPanel = useCallback(() => dispatch({ type: 'TOGGLE_REVIEW_PANEL' }), [])
  const toggleTestPanel = useCallback(() => dispatch({ type: 'TOGGLE_TEST_PANEL' }), [])
  const closeAllPanels = useCallback(() => dispatch({ type: 'CLOSE_ALL_PANELS' }), [])
  const clearCurrent = useCallback(() => dispatch({ type: 'CLEAR_CURRENT' }), [])

  const openProject = useCallback((project: Project) => {
    dispatch({ type: 'SET', key: 'currentProject', value: project })
    if (project) {
      const exists = state.recentProjects.find(p => p.path === project.path)
      if (!exists) {
        const updated = [{ ...project, lastOpened: Date.now() }, ...state.recentProjects].slice(0, 10)
        dispatch({ type: 'SET', key: 'recentProjects', value: updated })
      }
    }
    persistSettings()
  }, [state.recentProjects, persistSettings])

  const closeProject = useCallback(() => {
    dispatch({ type: 'SET_MULTIPLE', payload: { currentProject: null, fileTree: [] } })
    persistSettings()
  }, [persistSettings])

  const loadFileTree = useCallback(async () => {
    if (!state.currentProject) return
    try {
      const data = await fetch(`/api/fs?path=${encodeURIComponent(state.currentProject.path)}`).then(r => r.json())
      dispatch({ type: 'SET', key: 'fileTree', value: data.entries || [] })
    } catch {
      dispatch({ type: 'SET', key: 'fileTree', value: [] })
    }
  }, [state.currentProject])

  const openFile = useCallback((path: string) => {
    const name = path.split('/').pop() || ''
    dispatch({ type: 'OPEN_FILE', file: { path, name, modified: false } })
  }, [])

  const closeFile = useCallback((path: string) => {
    dispatch({ type: 'CLOSE_FILE', path })
  }, [])

  const set = useCallback(<K extends keyof AppState>(key: K, value: AppState[K]) => {
    dispatch({ type: 'SET', key, value })
  }, [])

  // Init theme on mount
  useEffect(() => {
    initTheme()
  }, [initTheme])

  // Persist settings on relevant changes
  useEffect(() => {
    persistSettings()
  }, [
    state.showReasoning, state.expandShellTools, state.expandEditTools,
    state.showProgress, state.notifAgent, state.notifPermissions, state.notifErrors,
    state.autoAcceptPermissions, state.shell, state.showFileTree, state.showSearch,
    state.showStatus, state.showCustomAgents, state.releaseNotes, state.serverUrl,
    state.servers, state.recentProjects, state.currentProject,
    persistSettings,
  ])

  const value: AppStore = useMemo(() => ({
    ...state,
    allThemes,
    currentTheme,
    filteredSessions,
    groupedSessions,
    persistSettings,
    initTheme,
    setTheme,
    toggleTheme,
    loadSessions,
    selectSession,
    loadMessages,
    createSession,
    deleteSession,
    updateSessionTitle,
    loadAgents,
    loadProviders,
    loadConfig,
    checkHealth,
    addNotification,
    toggleSidebarPanel,
    toggleSessionPanel,
    toggleReviewPanel,
    toggleTestPanel,
    closeAllPanels,
    clearCurrent,
    openProject,
    closeProject,
    loadFileTree,
    openFile,
    closeFile,
    set,
  }), [
    state, allThemes, currentTheme, filteredSessions, groupedSessions,
    persistSettings, initTheme, setTheme, toggleTheme,
    loadSessions, selectSession, loadMessages, createSession,
    deleteSession, updateSessionTitle, loadAgents, loadProviders,
    loadConfig, checkHealth, addNotification,
    toggleSidebarPanel, toggleSessionPanel, toggleReviewPanel,
    toggleTestPanel, closeAllPanels, clearCurrent,
    openProject, closeProject, loadFileTree, openFile, closeFile, set,
  ])

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>
}

export function useApp(): AppStore {
  const ctx = useContext(AppContext)
  if (!ctx) throw new Error('useApp must be used within AppProvider')
  return ctx
}
