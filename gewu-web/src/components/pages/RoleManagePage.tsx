'use client';
import { useState, useEffect, useCallback } from 'react';
import { Loader2, X, Plus, Trash2, KeyRound } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import { listRoles, listPermissions, assignPermissions, createRole, deleteRole, updateRole, type RoleDTO, type PermissionDTO } from '@/lib/role';

const DATA_SCOPE_OPTIONS = [
  { value: 1, label: '全部数据' },
  { value: 2, label: '本部门' },
  { value: 3, label: '本部门及以下' },
  { value: 4, label: '仅本人' },
];

function dataScopeLabel(scope?: number) {
  return DATA_SCOPE_OPTIONS.find(o => o.value === scope)?.label ?? '仅本人';
}

export default function RoleManagePage() {
  const toast = useToast();
  const [roles, setRoles] = useState<RoleDTO[]>([]);
  const [permissions, setPermissions] = useState<PermissionDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedRoleId, setSelectedRoleId] = useState<string | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [formName, setFormName] = useState('');
  const [formCode, setFormCode] = useState('');
  const [formDesc, setFormDesc] = useState('');
  const [saving, setSaving] = useState(false);
  const [scopeUpdating, setScopeUpdating] = useState(false);

  const selectedRole = roles.find(r => r.roleId === selectedRoleId) || null;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [r, p] = await Promise.all([listRoles(), listPermissions()]);
      setRoles(r || []);
      setPermissions(p || []);
      if (r && r.length > 0 && !selectedRoleId) setSelectedRoleId(r[0].roleId);
    } catch {
      toast('加载失败，可能无管理员权限', 'error');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  const handleTogglePermission = async (code: string) => {
    if (!selectedRole) return;
    const current = new Set(selectedRole.permissionCodes || []);
    if (current.has(code)) current.delete(code); else current.add(code);
    const next = Array.from(current);
    const updated = { ...selectedRole, permissionCodes: next };
    setRoles(roles.map(r => r.roleId === updated.roleId ? updated : r));
    try {
      await assignPermissions(selectedRole.roleId, next);
    } catch (e) {
      toast('分配失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
      load();
    }
  };

  const handleCreate = async () => {
    if (!formName.trim() || !formCode.trim()) return;
    setSaving(true);
    try {
      await createRole({ roleName: formName, roleCode: formCode, description: formDesc });
      toast('角色已创建', 'success');
      setShowCreateModal(false);
      setFormName(''); setFormCode(''); setFormDesc('');
      load();
    } catch (e) {
      toast('创建失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (role: RoleDTO) => {
    if (!confirm(`确认删除角色 ${role.roleName}？关联的用户与权限将被清理。`)) return;
    try {
      await deleteRole(role.roleId);
      toast('角色已删除', 'success');
      if (selectedRoleId === role.roleId) setSelectedRoleId(null);
      load();
    } catch (e) {
      toast('删除失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const handleDataScopeChange = async (roleId: string, dataScope: number) => {
    setScopeUpdating(true);
    try {
      await updateRole(roleId, { dataScope });
      setRoles(roles.map(r => r.roleId === roleId ? { ...r, dataScope } : r));
      toast('数据范围已更新', 'success');
    } catch (e) {
      toast('更新失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setScopeUpdating(false);
    }
  };

  // 按 resourceType 分组权限
  const groupedPermissions = permissions.reduce((acc, p) => {
    const key = p.resourceType || 'OTHER';
    if (!acc[key]) acc[key] = [];
    acc[key].push(p);
    return acc;
  }, {} as Record<string, PermissionDTO[]>);

  return (
    <div>
      <header className="flex items-center justify-between mb-8">
        <div><h1 className="text-2xl font-semibold text-ink-50 flex items-center gap-2"><KeyRound className="w-6 h-6 text-tech-400" />角色权限管理</h1><p className="text-ink-400 text-sm mt-1">配置角色与权限矩阵</p></div>
        <button onClick={() => setShowCreateModal(true)} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg"><Plus className="w-4 h-4" />创建角色</button>
      </header>

      {loading ? (
        <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
      ) : (
        <div className="flex gap-6">
          {/* 角色列表 */}
          <div className="w-64 flex-shrink-0">
            <p className="text-xs text-ink-500 mb-2 px-2">角色</p>
            <div className="space-y-1">
              {roles.map(r => (
                <button key={r.roleId} onClick={() => setSelectedRoleId(r.roleId)} className={`w-full text-left px-3 py-2.5 rounded-lg transition-all ${selectedRoleId === r.roleId ? 'bg-tech-500/15 text-tech-400 border border-tech-500/20' : 'text-ink-300 hover:bg-tech-500/5 border border-transparent'}`}>
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium">{r.roleName}</span>
                    {r.isSystem === 1 ? <span className="text-[10px] text-ink-500">系统</span> : <span className="text-[10px] text-ink-500">{r.userCount || 0}人</span>}
                  </div>
                  <div className="text-[10px] text-ink-500 mt-0.5">{r.roleCode} · {r.permissionCodes?.length || 0}权限 · {dataScopeLabel(r.dataScope)}</div>
                </button>
              ))}
            </div>
          </div>

          {/* 权限矩阵 */}
          <div className="flex-1">
            {selectedRole ? (
              <>
                <div className="flex items-center justify-between mb-4">
                  <div><h3 className="text-base font-semibold text-ink-50">{selectedRole.roleName}</h3><p className="text-xs text-ink-500">{selectedRole.description || '-'}</p></div>
                  {selectedRole.isSystem !== 1 && <button onClick={() => handleDelete(selectedRole)} className="flex items-center gap-1 px-3 py-1 text-xs border border-cinnabar-500/20 text-cinnabar-400 rounded-lg hover:bg-cinnabar-500/10"><Trash2 className="w-3 h-3" />删除角色</button>}
                </div>

                {/* 数据范围配置 */}
                <div className="glass-dark rounded-xl p-4 mb-4">
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="text-sm text-ink-200 font-medium">数据范围</p>
                      <p className="text-[11px] text-ink-500 mt-0.5">控制该角色用户可查询的数据范围（@DataPermission 自动过滤）</p>
                    </div>
                    <div className="flex items-center gap-2">
                      {scopeUpdating && <Loader2 className="w-3.5 h-3.5 text-tech-400 animate-spin" />}
                      <select
                        value={selectedRole.dataScope ?? 4}
                        disabled={scopeUpdating}
                        onChange={e => handleDataScopeChange(selectedRole.roleId, Number(e.target.value))}
                        className="px-3 py-1.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30 cursor-pointer"
                      >
                        {DATA_SCOPE_OPTIONS.map(o => (
                          <option key={o.value} value={o.value}>{o.label}</option>
                        ))}
                      </select>
                    </div>
                  </div>
                </div>

                <div className="space-y-4">
                  {Object.entries(groupedPermissions).map(([type, perms]) => (
                    <div key={type} className="glass-dark rounded-xl p-4">
                      <p className="text-xs text-ink-400 mb-3 font-medium">{type}</p>
                      <div className="grid grid-cols-2 gap-2">
                        {perms.map(p => {
                          const checked = selectedRole.permissionCodes?.includes(p.permissionCode);
                          return (
                            <label key={p.permissionId} className="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-tech-500/5 cursor-pointer">
                              <input type="checkbox" checked={!!checked} onChange={() => handleTogglePermission(p.permissionCode)} className="accent-tech-400" />
                              <div><div className="text-xs text-ink-200">{p.permissionName}</div><div className="text-[10px] text-ink-500">{p.permissionCode}</div></div>
                            </label>
                          );
                        })}
                      </div>
                    </div>
                  ))}
                </div>
              </>
            ) : <div className="text-center py-20 text-ink-500 text-sm">请选择左侧角色</div>}
          </div>
        </div>
      )}

      {showCreateModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowCreateModal(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-md p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-semibold text-ink-50">创建角色</h3>
              <button onClick={() => setShowCreateModal(false)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
            </div>
            <div className="space-y-3">
              <div><label className="block text-xs text-ink-400 mb-1">角色名称 <span className="text-cinnabar-400">*</span></label><input value={formName} onChange={e => setFormName(e.target.value)} className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">角色编码 <span className="text-cinnabar-400">*</span></label><input value={formCode} onChange={e => setFormCode(e.target.value)} placeholder="如 DEVELOPER" className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">描述</label><input value={formDesc} onChange={e => setFormDesc(e.target.value)} className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
            </div>
            <div className="flex justify-end gap-3 mt-4">
              <button onClick={() => setShowCreateModal(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleCreate} disabled={saving || !formName.trim() || !formCode.trim()} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2">{saving && <Loader2 className="w-4 h-4 animate-spin" />}创建</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
