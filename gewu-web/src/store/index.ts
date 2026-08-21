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
  chatSessions: [
    { id: '1', title: 'Q3 产品规划文档撰写', messages: [
      { id: '1', role: 'ai' as const, content: '您好！我是文档助手。我已了解您需要撰写 Q3 产品规划文档。让我先为您梳理框架：\n\n1. 市场分析与竞品调研\n2. 产品目标与 OKR 设定\n3. 核心功能路线图\n4. 资源规划与里程碑\n\n请问您希望从哪个部分开始？', timestamp: '10:30' },
      { id: '2', role: 'user' as const, content: '从市场分析开始吧。我们主要关注金融科技领域的竞品，特别是支付和财富管理方向。', timestamp: '10:32' },
    ]},
  ],
  activeSessionId: '1',
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
