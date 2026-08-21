'use client';
import type { ReactNode } from 'react';
import { usePermission } from '@/lib/usePermission';

interface Props {
  /** 所需权限码 */
  code: string;
  children: ReactNode;
  /** 无权限时渲染的兜底内容 */
  fallback?: ReactNode;
}

/**
 * 按权限码控制子节点渲染.
 * 用法：<Permission code="user:manage"><button>删除</button></Permission>
 */
export default function Permission({ code, children, fallback = null }: Props) {
  const { hasPermission } = usePermission();
  return hasPermission(code) ? <>{children}</> : <>{fallback}</>;
}
