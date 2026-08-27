import { configureStore, createSlice, PayloadAction } from '@reduxjs/toolkit';
import { PageType, ThemeType, Message, User, PrototypeVersion } from '@/types';
import { getUser, clearTokens, isTokenValid } from '@/lib/token';

interface ChatSession { id: string; title: string; messages: Message[]; }

interface AppState {
  currentPage: PageType;
  theme: ThemeType;
  user: User;
  isAuthenticated: boolean;
  sidebarOpen: boolean;
  messages: Message[];
  chatSessions: ChatSession[];
  activeSessionId: string;
  projects: PrototypeVersion[];
  /** 跨页传递"要对话的智能体 ID"，ChatPage 读取后自动选中并清空 */
  pendingAgentId?: string | null;
}

// 从 localStorage 恢复用户状态（同时检查令牌是否过期）
function getInitialUser(): { user: User; isAuthenticated: boolean } {
  if (typeof window === 'undefined') {
    return { user: { name: '', role: '', department: '', avatar: '' }, isAuthenticated: false };
  }
  const stored = getUser();
  // 检查令牌是否有效（存在且未过期）
  if (stored && isTokenValid()) {
    return {
      user: {
        userId: stored.userId,
        username: stored.username,
        displayName: stored.displayName,
        roles: stored.roles,
        permissions: stored.permissions || [],
        name: stored.displayName,
        role: stored.roles?.[0] || '',
        department: '',
        avatar: stored.displayName?.charAt(0) || '',
      },
      isAuthenticated: true,
    };
  }
  // 令牌无效时清除本地存储
  if (stored || typeof window !== 'undefined') {
    clearTokens();
  }
  return { user: { name: '', role: '', department: '', avatar: '' }, isAuthenticated: false };
}

const initialAuth = getInitialUser();

const initialState: AppState = {
  currentPage: initialAuth.isAuthenticated ? 'dashboard' : 'login',
  theme: (typeof window !== 'undefined' ? localStorage.getItem('gewu-theme') as ThemeType : null) || 'ink',
  user: initialAuth.user,
  isAuthenticated: initialAuth.isAuthenticated,
  sidebarOpen: true,
  messages: [],
  // 会话列表由 ChatPage 从后端拉取（原 mock 数据已清理，T4.4）
  chatSessions: [],
  activeSessionId: null,
  projects: [],
  pendingAgentId: null,
};

const appSlice = createSlice({
  name: 'app',
  initialState,
  reducers: {
    setPage: (state, action: PayloadAction<PageType>) => { state.currentPage = action.payload; },
    setTheme: (state, action: PayloadAction<ThemeType>) => {
      state.theme = action.payload;
      if (typeof window !== 'undefined') localStorage.setItem('gewu-theme', action.payload);
    },
    // 登录成功，更新用户状态
    setAuth: (state, action: PayloadAction<{ user: User; isAuthenticated: boolean }>) => {
      state.user = action.payload.user;
      state.isAuthenticated = action.payload.isAuthenticated;
      state.currentPage = 'dashboard';
    },
    // 登出，清除用户状态
    clearAuth: (state) => {
      clearTokens();
      state.user = { name: '', role: '', department: '', avatar: '' };
      state.isAuthenticated = false;
      state.currentPage = 'login';
    },
    addMessage: (state, action: PayloadAction<Message>) => { state.messages.push(action.payload); },
    setProjects: (state, action: PayloadAction<PrototypeVersion[]>) => { state.projects = action.payload; },
    addChatMessage: (state, action: PayloadAction<{ sessionId: string; message: Message }>) => {
      const session = state.chatSessions.find(s => s.id === action.payload.sessionId);
      if (session) session.messages.push(action.payload.message);
    },
    setActiveSession: (state, action: PayloadAction<string>) => { state.activeSessionId = action.payload; },
    setPendingAgentId: (state, action: PayloadAction<string | null>) => { state.pendingAgentId = action.payload; },
    createChatSession: (state, action: PayloadAction<{ id: string; title: string }>) => {
      state.chatSessions.unshift({ id: action.payload.id, title: action.payload.title, messages: [] });
      state.activeSessionId = action.payload.id;
    },
  },
});

export const { setPage, setTheme, setAuth, clearAuth, addMessage, setProjects, addChatMessage, setActiveSession, createChatSession, setPendingAgentId } = appSlice.actions;
export const store = configureStore({ reducer: { app: appSlice.reducer } });
export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
