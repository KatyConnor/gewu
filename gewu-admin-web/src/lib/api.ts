// API 后端服务配置
// 后端服务地址：http://localhost:8081
// 前端通过 /api 代理转发到后端（见 next.config.mjs rewrites）

export const API_CONFIG = {
  // 开发环境使用代理路径（由 next.config.mjs 转发到 localhost:8081）
  BASE_URL: '/api',
  // 生产环境可直接配置完整地址
  // BASE_URL: 'http://localhost:8081/api',
  TIMEOUT: 30000,
} as const;

export const API_ENDPOINTS = {
  // 认证相关
  AUTH: '/v1/auth',
  AUTH_LOGIN: '/v1/auth/login',
  AUTH_REGISTER: '/v1/auth/register',
  AUTH_REFRESH: '/v1/auth/refresh',
  AUTH_LOGOUT: '/v1/auth/logout',
  // 工作流相关
          // 智能体相关
  AGENTS: '/v1/agents',
  AGENT_MARKET: '/v1/agents/market',
  // 技能相关
  SKILLS: '/v1/skills',
  SKILL_LIBRARY: '/v1/skills/library',
  // 项目相关
  PROJECTS: '/v1/projects',
  // 会话相关
  CHAT: '/v1/ai/chat',
  CHAT_STREAM: '/v1/ai/chat/stream',
  CHAT_MODELS: '/v1/ai/models',
  CHAT_SESSIONS: '/v1/chat/sessions',
  // 用户相关
  USERS: '/v1/users',
  // 统计相关
  USAGE: '/v1/usage',
  STATISTICS: '/v1/statistics',
  // MCP Server
  MCP_SERVER: '/v1/mcp-servers',
  // 沙箱
  SANDBOX: '/v1/sandboxes',
} as const;
