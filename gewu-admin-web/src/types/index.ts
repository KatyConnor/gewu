export type PageType = 'login' | 'dashboard' | 'user-manage' | 'role-manage' | 'menu-manage' | 'org-manage' | 'skill-audit' | 'quota-manage' | 'sandbox-audit';

export type ThemeType = 'ink' | 'deepsea' | 'jade' | 'celadon';

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
