'use client';
import { useState, useEffect, useCallback } from 'react';
import { Search, Loader2, X, KeyRound, ShieldCheck, Building2 } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import { listUsers, updateUserStatus, resetPassword, assignRoles, assignOrg, ROLE_OPTIONS, type UserDTO } from '@/lib/user';
import { listOrgTree, type OrgDTO } from '@/lib/org';

const statusConfig: Record<number, { label: string; color: string }> = {
  1: { label: '启用', color: 'text-green-400' },
  2: { label: '禁用', color: 'text-cinnabar-400' },
  3: { label: '锁定', color: 'text-gold-400' },
};

function formatTime(ts?: number): string {
  if (!ts) return '-';
  try { return new Date(ts).toLocaleString('zh-CN'); } catch { return '-'; }
}

export default function UserManagePage() {
  const toast = useToast();
  const [users, setUsers] = useState<UserDTO[]>([]);
  const [orgs, setOrgs] = useState<OrgDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [filterOrgId, setFilterOrgId] = useState('');
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [roleModalUser, setRoleModalUser] = useState<UserDTO | null>(null);
  const [selectedRoles, setSelectedRoles] = useState<Set<string>>(new Set());
  const [orgModalUser, setOrgModalUser] = useState<UserDTO | null>(null);
  const [selectedOrgId, setSelectedOrgId] = useState('');
  const [saving, setSaving] = useState(false);

  const loadUsers = useCallback(async (p = 1, kw?: string, oid?: string) => {
    setLoading(true);
    try {
      const data = await listUsers(p, 20, kw, oid || undefined);
      setUsers(data.records || []);
      setTotal(data.total || 0);
      setPage(p);
    } catch (e) {
      console.error('加载用户失败:', e);
      setUsers([]);
      toast('加载失败，可能无管理员权限', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    loadUsers(1);
    listOrgTree().then(setOrgs).catch(() => {});
  }, [loadUsers]);

  const handleSearch = () => loadUsers(1, keyword, filterOrgId);

  const handleToggleStatus = async (user: UserDTO) => {
    const newStatus = user.status === 1 ? 2 : 1;
    try {
      await updateUserStatus(user.userId, newStatus);
      toast(newStatus === 1 ? '已启用' : '已禁用', 'success');
      loadUsers(page, keyword, filterOrgId);
    } catch (e) {
      toast('操作失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const handleResetPassword = async (user: UserDTO) => {
    const pwd = prompt(`重置 ${user.username} 的密码（8-64位）`);
    if (!pwd) return;
    if (pwd.length < 8) { toast('密码至少 8 位', 'error'); return; }
    try {
      await resetPassword(user.userId, pwd);
      toast('密码已重置', 'success');
    } catch (e) {
      toast('重置失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const openRoleModal = (user: UserDTO) => {
    setRoleModalUser(user);
    setSelectedRoles(new Set(user.roleCodes || []));
  };

  const toggleRole = (code: string) => {
    const next = new Set(selectedRoles);
    if (next.has(code)) next.delete(code); else next.add(code);
    setSelectedRoles(next);
  };

  const handleSaveRoles = async () => {
    if (!roleModalUser) return;
    setSaving(true);
    try {
      await assignRoles(roleModalUser.userId, Array.from(selectedRoles));
      toast('角色已更新', 'success');
      setRoleModalUser(null);
      loadUsers(page, keyword, filterOrgId);
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  const openOrgModal = (user: UserDTO) => {
    setOrgModalUser(user);
    setSelectedOrgId(user.orgId || '');
  };

  const handleSaveOrg = async () => {
    if (!orgModalUser) return;
    setSaving(true);
    try {
      await assignOrg(orgModalUser.userId, selectedOrgId || null);
      toast('机构已分配', 'success');
      setOrgModalUser(null);
      loadUsers(page, keyword, filterOrgId);
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  // 扁平化机构树（用于筛选和分配下拉）
  const flatOrgs: { id: string; label: string; depth: number }[] = [];
  const collectFlat = (items: OrgDTO[], depth: number) => {
    items.forEach(o => {
      flatOrgs.push({ id: o.orgId, label: o.orgName, depth });
      collectFlat(o.children || [], depth + 1);
    });
  };
  collectFlat(orgs, 0);

  const totalPages = Math.max(1, Math.ceil(total / 20));

  return (
    <div>
      <header className="mb-8">
        <h1 className="text-2xl font-semibold text-ink-50 flex items-center gap-2"><ShieldCheck className="w-6 h-6 text-tech-400" />用户管理</h1>
        <p className="text-ink-400 text-sm mt-1">管理平台用户、状态、角色与机构分配</p>
      </header>

      <div className="flex items-center gap-3 mb-6">
        <div className="flex items-center gap-2 px-3 py-2 bg-ink-800/50 rounded-lg border border-tech-500/10 flex-1 max-w-sm">
          <Search className="w-4 h-4 text-ink-500" />
          <input type="text" value={keyword} onChange={e => setKeyword(e.target.value)} onKeyDown={e => e.key === 'Enter' && handleSearch()} placeholder="搜索用户名/邮箱..." className="bg-transparent text-sm text-ink-200 placeholder-ink-500 outline-none flex-1" />
        </div>
        <select
          value={filterOrgId}
          onChange={e => { setFilterOrgId(e.target.value); loadUsers(1, keyword, e.target.value); }}
          className="px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-200 outline-none focus:border-tech-500/30"
        >
          <option value="">全部机构</option>
          {flatOrgs.map(o => (
            <option key={o.id} value={o.id}>{'　'.repeat(o.depth)}{o.label}</option>
          ))}
        </select>
        <button onClick={handleSearch} className="px-4 py-2 btn-primary text-white text-sm rounded-lg">搜索</button>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
      ) : users.length === 0 ? (
        <div className="text-center py-20 text-ink-500 text-sm">暂无用户数据</div>
      ) : (
        <div className="glass-dark rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-xs text-ink-500 border-b border-tech-500/10">
                <th className="text-left px-4 py-3 font-medium">用户名</th>
                <th className="text-left px-4 py-3 font-medium">邮箱</th>
                <th className="text-left px-4 py-3 font-medium">机构</th>
                <th className="text-left px-4 py-3 font-medium">角色</th>
                <th className="text-left px-4 py-3 font-medium">状态</th>
                <th className="text-left px-4 py-3 font-medium">最后登录</th>
                <th className="text-right px-4 py-3 font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {users.map(u => {
                const st = statusConfig[u.status] || statusConfig[1];
                return (
                  <tr key={u.userId} className="border-b border-tech-500/5 hover:bg-tech-500/5">
                    <td className="px-4 py-3"><div className="font-medium text-ink-100">{u.username}</div><div className="text-xs text-ink-500">{u.displayName || '-'}</div></td>
                    <td className="px-4 py-3 text-ink-300">{u.email}</td>
                    <td className="px-4 py-3 text-ink-300">{u.orgName || <span className="text-ink-500">-</span>}</td>
                    <td className="px-4 py-3"><div className="flex flex-wrap gap-1">{(u.roleCodes || []).map(r => <span key={r} className="text-[10px] px-1.5 py-0.5 bg-tech-500/10 text-tech-400 rounded">{r}</span>)}</div></td>
                    <td className="px-4 py-3"><span className={`text-xs ${st.color}`}>● {st.label}</span></td>
                    <td className="px-4 py-3 text-xs text-ink-500">{formatTime(u.lastLoginAt)}</td>
                    <td className="px-4 py-3 text-right">
                      <div className="flex items-center justify-end gap-2">
                        <button onClick={() => handleToggleStatus(u)} className={`text-xs px-2 py-1 rounded border ${u.status === 1 ? 'border-cinnabar-500/20 text-cinnabar-400 hover:bg-cinnabar-500/10' : 'border-green-500/20 text-green-400 hover:bg-green-500/10'}`}>{u.status === 1 ? '禁用' : '启用'}</button>
                        <button onClick={() => handleResetPassword(u)} className="text-xs px-2 py-1 rounded border border-tech-500/15 text-tech-400 hover:bg-tech-500/10 flex items-center gap-1"><KeyRound className="w-3 h-3" />重置密码</button>
                        <button onClick={() => openRoleModal(u)} className="text-xs px-2 py-1 rounded border border-tech-500/15 text-ink-300 hover:bg-tech-500/10">角色</button>
                        <button onClick={() => openOrgModal(u)} className="text-xs px-2 py-1 rounded border border-tech-500/15 text-ink-300 hover:bg-tech-500/10 flex items-center gap-1"><Building2 className="w-3 h-3" />机构</button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {!loading && total > 20 && (
        <div className="flex items-center justify-between mt-4">
          <span className="text-xs text-ink-500">共 {total} 条</span>
          <div className="flex gap-2">
            <button onClick={() => loadUsers(page - 1, keyword, filterOrgId)} disabled={page <= 1} className="px-3 py-1 text-xs border border-tech-500/15 text-ink-300 rounded-lg disabled:opacity-40">上一页</button>
            <span className="text-xs text-ink-400 px-2 py-1">{page} / {totalPages}</span>
            <button onClick={() => loadUsers(page + 1, keyword, filterOrgId)} disabled={page >= totalPages} className="px-3 py-1 text-xs border border-tech-500/15 text-ink-300 rounded-lg disabled:opacity-40">下一页</button>
          </div>
        </div>
      )}

      {roleModalUser && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setRoleModalUser(null)}>
          <div className="glass-dark rounded-2xl w-full max-w-md p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-semibold text-ink-50">分配角色 - {roleModalUser.username}</h3>
              <button onClick={() => setRoleModalUser(null)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
            </div>
            <div className="space-y-2 max-h-72 overflow-y-auto scrollbar-thin">
              {ROLE_OPTIONS.map(r => (
                <label key={r.value} className="flex items-center gap-3 px-3 py-2 rounded-lg hover:bg-tech-500/5 cursor-pointer">
                  <input type="checkbox" checked={selectedRoles.has(r.value)} onChange={() => toggleRole(r.value)} className="accent-tech-400" />
                  <span className="text-sm text-ink-200">{r.label}</span>
                  <span className="text-xs text-ink-500 ml-auto">{r.value}</span>
                </label>
              ))}
            </div>
            <div className="flex justify-end gap-3 mt-4">
              <button onClick={() => setRoleModalUser(null)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleSaveRoles} disabled={saving} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2">{saving && <Loader2 className="w-4 h-4 animate-spin" />}保存</button>
            </div>
          </div>
        </div>
      )}

      {orgModalUser && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setOrgModalUser(null)}>
          <div className="glass-dark rounded-2xl w-full max-w-md p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-semibold text-ink-50">分配机构 - {orgModalUser.username}</h3>
              <button onClick={() => setOrgModalUser(null)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
            </div>
            <div>
              <label className="block text-xs text-ink-400 mb-1">所属机构</label>
              <select
                value={selectedOrgId}
                onChange={e => setSelectedOrgId(e.target.value)}
                className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30"
              >
                <option value="">（未分配）</option>
                {flatOrgs.map(o => (
                  <option key={o.id} value={o.id}>{'　'.repeat(o.depth)}{o.label}</option>
                ))}
              </select>
            </div>
            <div className="flex justify-end gap-3 mt-4">
              <button onClick={() => setOrgModalUser(null)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleSaveOrg} disabled={saving} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2">{saving && <Loader2 className="w-4 h-4 animate-spin" />}保存</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
