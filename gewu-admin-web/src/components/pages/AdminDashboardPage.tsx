'use client';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/Card';

/** 后台管理总览（占位）：后续可接入全局统计 */
export default function AdminDashboardPage() {
  return (
    <div>
      <header className="mb-8">
        <h1 className="text-2xl font-semibold text-ink-50">后台管理总览</h1>
        <p className="text-ink-400 text-sm mt-1">系统管理与沙箱安全审计控制台</p>
      </header>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {['用户与机构', '角色权限与菜单', '配额套餐与审计'].map(t => (
          <Card key={t}>
            <CardHeader><CardTitle>{t}</CardTitle></CardHeader>
            <CardContent><p className="text-xs text-ink-500">从左侧导航进入对应功能页。</p></CardContent>
          </Card>
        ))}
      </div>
    </div>
  );
}
