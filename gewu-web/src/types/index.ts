import type { ProcessItem } from '@/lib/agentProcess';

export type PageType = 'login' | 'dashboard' | 'chat' | 'projects' | 'settings' | 'agent-market' | 'agent-manage' | 'my-agents' | 'skill-library' | 'my-skills' | 'prototype' | 'requirements' | 'workflow' | 'orchestration' | 'usage' | 'sandbox' | 'mcp-server' | 'skill-audit' | 'user-manage' | 'role-manage' | 'audit-center' | 'menu-manage' | 'org-manage' | 'workspace' | 'dev-workspace';

export type ThemeType = 'ink' | 'deepsea' | 'jade' | 'celadon';

export interface SearchItem {
  url: string;
  title: string;
  snippet: string;
  source: string;
  adopted: boolean;
  discarded: boolean;
  discardReason?: string;
  confidence: number;
}

export interface FileInfo {
  fileName: string;
  fileType: string;
  mimeType: string;
  fileSize: number;
  downloadUrl: string;
  previewContent: string;
  source: string;
}

export interface Message {
  id: string;
  role: 'user' | 'ai';
  content: string;
  timestamp: string;
  /** AI 处理过程时间线（思考/工具/搜索，实时累积） */
  process?: ProcessItem[];
  /** 处理过程总耗时（毫秒） */
  processMs?: number;
  processExpanded?: boolean;
  files?: FileInfo[];
}

export interface Project {
  id: string;
  name: string;
  description: string;
  category: string;
  progress: number;
  members: string[];
  status: '进行中' | '待开始' | '已完成';
}

export interface PrototypeVersion {
  version: string;
  date: string;
  description: string;
  changes: { type: 'add' | 'mod' | 'remove'; text: string }[];
}

export interface User {
  // 后端返回字段
  userId?: string;
  username?: string;
  displayName?: string;
  roles?: string[];
  permissions?: string[];
  // 前端展示字段（兼容旧代码）
  name: string;
  role: string;
  department: string;
  avatar: string;
}
