import type { ProcessItem } from '@/lib/agentProcess';
import type { TurnFileChangeDTO } from '@/lib/sessionFileChanges';

export type PageType = 'login' | 'dashboard' | 'chat' | 'projects' | 'settings' | 'agent-market' | 'agent-manage' | 'my-agents' | 'skill-library' | 'my-skills' | 'prototype' | 'requirements' | 'workflow' | 'orchestration' | 'usage' | 'sandbox' | 'mcp-server' | 'audit-center' | 'workspace' | 'dev-workspace';

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

/** 执行统计（完成透明度：done 事件/消息 metadata 携带，区分"AI 自主收尾"与"被限制收尾"） */
export interface ExecutionStats {
  /** 工具调用轮次 */
  rounds?: number;
  /** 执行总耗时（毫秒） */
  elapsedMs?: number;
  /** 估算 token 消耗（流式按字符量折算） */
  tokenEstimated?: number;
  /** 时间预算滚动续期次数 */
  timeRenewals?: number;
  /** 轮次预算滚动扩容次数 */
  roundsRenewed?: number;
  /** token 预算是否不限量（未绑定套餐用户） */
  tokenUnlimited?: boolean;
}

export interface Message {
  id: string;
  role: 'user' | 'ai';
  content: string;
  timestamp: string;
  /** 是否来自后端持久化（历史消息为 true，流式新消息为本地伪 id；重发按钮仅对后端消息可用） */
  fromBackend?: boolean;
  /** AI 处理过程时间线（思考/工具/搜索，实时累积） */
  process?: ProcessItem[];
  /** 处理过程总耗时（毫秒） */
  processMs?: number;
  processExpanded?: boolean;
  files?: FileInfo[];
  /** 执行统计（AI 消息尾部"自主完成收尾"状态行数据源） */
  stats?: ExecutionStats;
  /** 回合文件汇总（撤销功能：metadata.turnFiles 持久化，AI 消息尾部汇总条数据源） */
  turnFiles?: TurnFileChangeDTO[];
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
