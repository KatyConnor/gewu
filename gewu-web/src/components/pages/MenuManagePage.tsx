'use client';
import { useState, useEffect, useCallback } from 'react';
import { Loader2, X, Plus, Trash2, Menu as MenuIcon, Edit3, ChevronRight, ChevronDown } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import {
  listMenus, createMenu, updateMenu, deleteMenu,
  listRoleMenus, assignRoleMenus,
  type MenuDTO,
} from '@/lib/menu';
import { listRoles, type RoleDTO } from '@/lib/role';

const MENU_TYPE_LABELS: Record<number, string> = { 1: '目录', 2: '菜单', 3: '按钮' };

export default function MenuManagePage() {
  const toast = useToast();
  const [menus, setMenus] = useState<MenuDTO[]>([]);
  const [roles, setRoles] = useState<RoleDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<'config' | 'assign'>('config');

  // 角色菜单分配
  const [selectedRoleId, setSelectedRoleId] = useState<string | null>(null);
  const [assignedMenuIds, setAssignedMenuIds] = useState<Set<string>>(new Set());
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [assignSaving, setAssignSaving] = useState(false);

  // 菜单编辑/创建
  const [editingMenu, setEditingMenu] = useState<MenuDTO | null>(null);
  const [showModal, setShowModal] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({
    parentId: '',
    menuName: '',
    menuType: 2,
    path: '',
    icon: '',
    sortOrder: 0,
    permissionCode: '',
    visible: 1,
  });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [m, r] = await Promise.all([listMenus(), listRoles()]);
      setMenus(m || []);
      setRoles(r || []);
      if (r && r.length > 0 && !selectedRoleId) setSelectedRoleId(r[0].roleId);
    } catch {
      toast('加载失败，可能无菜单管理权限', 'error');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  // 加载角色已分配菜单
  useEffect(() => {
    if (!selectedRoleId || tab !== 'assign') return;
    listRoleMenus(selectedRoleId)
      .then(ids => setAssignedMenuIds(new Set(ids || [])))
      .catch(() => toast('加载角色菜单失败', 'error'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedRoleId, tab]);

  // ==================== 菜单配置 Tab ====================

  const openCreate = (parentId?: string) => {
    setEditingMenu(null);
    setForm({ parentId: parentId || '', menuName: '', menuType: 2, path: '', icon: '', sortOrder: 0, permissionCode: '', visible: 1 });
    setShowModal(true);
  };

  const openEdit = (menu: MenuDTO) => {
    setEditingMenu(menu);
    setForm({
      parentId: menu.parentId || '',
      menuName: menu.menuName,
      menuType: menu.menuType || 2,
      path: menu.path || '',
      icon: menu.icon || '',
      sortOrder: menu.sortOrder || 0,
      permissionCode: menu.permissionCode || '',
      visible: menu.visible ?? 1,
    });
    setShowModal(true);
  };

  const handleSave = async () => {
    if (!form.menuName.trim()) return;
    setSaving(true);
    try {
      const data = {
        parentId: form.parentId || undefined,
        menuName: form.menuName,
        menuType: form.menuType,
        path: form.path || undefined,
        icon: form.icon || undefined,
        sortOrder: form.sortOrder,
        permissionCode: form.permissionCode || undefined,
        visible: form.visible,
      };
      if (editingMenu) {
        await updateMenu(editingMenu.menuId, data);
        toast('菜单已更新', 'success');
      } else {
        await createMenu(data);
        toast('菜单已创建', 'success');
      }
      setShowModal(false);
      load();
    } catch (e) {
      toast((editingMenu ? '更新' : '创建') + '失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (menu: MenuDTO) => {
    if (!confirm(`确认删除菜单「${menu.menuName}」？`)) return;
    try {
      await deleteMenu(menu.menuId);
      toast('菜单已删除', 'success');
      load();
    } catch (e) {
      toast('删除失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  // ==================== 角色分配 Tab ====================

  const toggleExpand = (id: string) => {
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleMenuCheck = (menu: MenuDTO, checked: boolean) => {
    setAssignedMenuIds(prev => {
      const next = new Set(prev);
      // 切换自身及所有子孙
      const toggle = (m: MenuDTO) => {
        if (checked) next.add(m.menuId); else next.delete(m.menuId);
        (m.children || []).forEach(toggle);
      };
      toggle(menu);
      return next;
    });
  };

  const handleSaveAssign = async () => {
    if (!selectedRoleId) return;
    setAssignSaving(true);
    try {
      await assignRoleMenus(selectedRoleId, Array.from(assignedMenuIds));
      toast('角色菜单已保存', 'success');
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setAssignSaving(false);
    }
  };

  // ==================== 渲染 ====================

  const renderMenuTree = (items: MenuDTO[], depth: number, mode: 'config' | 'assign'): React.ReactNode => {
    return items.map(menu => {
      const hasChildren = menu.children && menu.children.length > 0;
      const isExpanded = expandedIds.has(menu.menuId);
      const isChecked = assignedMenuIds.has(menu.menuId);
      return (
        <div key={menu.menuId}>
          <div
            className="flex items-center gap-2 px-2 py-1.5 rounded hover:bg-tech-500/5 transition-colors"
            style={{ paddingLeft: `${depth * 20 + 8}px` }}
          >
            {mode === 'assign' && (
              <input
                type="checkbox"
                checked={isChecked}
                onChange={e => toggleMenuCheck(menu, e.target.checked)}
                className="accent-tech-400 flex-shrink-0"
              />
            )}
            {hasChildren ? (
              <button onClick={() => toggleExpand(menu.menuId)} className="text-ink-500 hover:text-tech-400 flex-shrink-0">
                {isExpanded ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
              </button>
            ) : (
              <span className="w-3.5 flex-shrink-0" />
            )}
            <span className={`text-xs flex-shrink-0 px-1.5 py-0.5 rounded ${menu.menuType === 1 ? 'bg-tech-500/10 text-tech-400' : 'bg-ink-700/50 text-ink-400'}`}>
              {MENU_TYPE_LABELS[menu.menuType || 2]}
            </span>
            <span className={`text-sm flex-1 truncate ${menu.visible === 0 ? 'text-ink-500 line-through' : 'text-ink-200'}`}>
              {menu.menuName}
            </span>
            {menu.path && <span className="text-[10px] text-ink-500 flex-shrink-0">{menu.path}</span>}
            {menu.permissionCode && <span className="text-[10px] text-cinnabar-400/70 flex-shrink-0">{menu.permissionCode}</span>}
            {mode === 'config' && (
              <>
                <button onClick={() => openCreate(menu.menuId)} className="p-1 text-ink-500 hover:text-tech-400 flex-shrink-0" title="添加子菜单">
                  <Plus className="w-3.5 h-3.5" />
                </button>
                <button onClick={() => openEdit(menu)} className="p-1 text-ink-500 hover:text-tech-400 flex-shrink-0" title="编辑">
                  <Edit3 className="w-3.5 h-3.5" />
                </button>
                <button onClick={() => handleDelete(menu)} className="p-1 text-ink-500 hover:text-cinnabar-400 flex-shrink-0" title="删除">
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </>
            )}
          </div>
          {hasChildren && isExpanded && renderMenuTree(menu.children, depth + 1, mode)}
        </div>
      );
    });
  };

  // 扁平化菜单用于父菜单下拉
  const flatMenus: { id: string; label: string; depth: number }[] = [];
  const collectFlat = (items: MenuDTO[], depth: number) => {
    items.forEach(m => {
      flatMenus.push({ id: m.menuId, label: m.menuName, depth });
      if (m.children) collectFlat(m.children, depth + 1);
    });
  };
  collectFlat(menus, 0);

  return (
    <div>
      <header className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-2xl font-semibold text-ink-50 flex items-center gap-2">
            <MenuIcon className="w-6 h-6 text-tech-400" />菜单管理
          </h1>
          <p className="text-ink-400 text-sm mt-1">配置菜单树与角色菜单分配</p>
        </div>
        {tab === 'config' && (
          <button onClick={() => openCreate()} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg">
            <Plus className="w-4 h-4" />创建菜单
          </button>
        )}
        {tab === 'assign' && (
          <button onClick={handleSaveAssign} disabled={assignSaving} className="flex items-center gap-2 px-4 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50">
            {assignSaving && <Loader2 className="w-4 h-4 animate-spin" />}保存分配
          </button>
        )}
      </header>

      {/* Tab 切换 */}
      <div className="flex gap-1 mb-4 border-b border-tech-500/10">
        <button
          onClick={() => setTab('config')}
          className={`px-4 py-2 text-sm transition-all ${tab === 'config' ? 'text-tech-400 border-b-2 border-tech-400' : 'text-ink-400 hover:text-ink-200'}`}
        >
          菜单配置
        </button>
        <button
          onClick={() => setTab('assign')}
          className={`px-4 py-2 text-sm transition-all ${tab === 'assign' ? 'text-tech-400 border-b-2 border-tech-400' : 'text-ink-400 hover:text-ink-200'}`}
        >
          角色菜单分配
        </button>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="w-6 h-6 text-tech-400 animate-spin" />
        </div>
      ) : tab === 'config' ? (
        <div className="glass-dark rounded-xl p-4">
          {menus.length === 0 ? (
            <div className="text-center py-10 text-ink-500 text-sm">暂无菜单数据</div>
          ) : (
            <div className="space-y-0.5">
              {renderMenuTree(menus, 0, 'config')}
            </div>
          )}
        </div>
      ) : (
        <div className="flex gap-6">
          {/* 角色列表 */}
          <div className="w-56 flex-shrink-0">
            <p className="text-xs text-ink-500 mb-2 px-2">角色</p>
            <div className="space-y-1">
              {roles.map(r => (
                <button
                  key={r.roleId}
                  onClick={() => setSelectedRoleId(r.roleId)}
                  className={`w-full text-left px-3 py-2.5 rounded-lg transition-all ${
                    selectedRoleId === r.roleId
                      ? 'bg-tech-500/15 text-tech-400 border border-tech-500/20'
                      : 'text-ink-300 hover:bg-tech-500/5 border border-transparent'
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium">{r.roleName}</span>
                    {r.isSystem === 1 && <span className="text-[10px] text-ink-500">系统</span>}
                  </div>
                  <div className="text-[10px] text-ink-500 mt-0.5">{r.roleCode}</div>
                </button>
              ))}
            </div>
          </div>
          {/* 菜单分配树 */}
          <div className="flex-1">
            <div className="glass-dark rounded-xl p-4">
              {menus.length === 0 ? (
                <div className="text-center py-10 text-ink-500 text-sm">暂无菜单数据</div>
              ) : (
                <div className="space-y-0.5">
                  {renderMenuTree(menus, 0, 'assign')}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* 创建/编辑弹窗 */}
      {showModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowModal(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-md p-6 shadow-2xl animate-fade-up border border-tech-500/10 max-h-[90vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-semibold text-ink-50">{editingMenu ? '编辑菜单' : '创建菜单'}</h3>
              <button onClick={() => setShowModal(false)} className="text-ink-500 hover:text-ink-300">
                <X className="w-5 h-5" />
              </button>
            </div>
            <div className="space-y-3">
              <div>
                <label className="block text-xs text-ink-400 mb-1">父菜单</label>
                <select
                  value={form.parentId}
                  onChange={e => setForm({ ...form, parentId: e.target.value })}
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30"
                >
                  <option value="">（顶级菜单）</option>
                  {flatMenus.map(m => (
                    <option key={m.id} value={m.id}>
                      {'　'.repeat(m.depth)}{m.label}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-xs text-ink-400 mb-1">菜单名称 <span className="text-cinnabar-400">*</span></label>
                <input
                  value={form.menuName}
                  onChange={e => setForm({ ...form, menuName: e.target.value })}
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30"
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-ink-400 mb-1">类型</label>
                  <select
                    value={form.menuType}
                    onChange={e => setForm({ ...form, menuType: Number(e.target.value) })}
                    className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30"
                  >
                    <option value={1}>目录</option>
                    <option value={2}>菜单</option>
                    <option value={3}>按钮</option>
                  </select>
                </div>
                <div>
                  <label className="block text-xs text-ink-400 mb-1">排序号</label>
                  <input
                    type="number"
                    value={form.sortOrder}
                    onChange={e => setForm({ ...form, sortOrder: Number(e.target.value) })}
                    className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30"
                  />
                </div>
              </div>
              <div>
                <label className="block text-xs text-ink-400 mb-1">路由标识（PageType）</label>
                <input
                  value={form.path}
                  onChange={e => setForm({ ...form, path: e.target.value })}
                  placeholder="如 dashboard / projects"
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30"
                />
              </div>
              <div>
                <label className="block text-xs text-ink-400 mb-1">图标名称</label>
                <input
                  value={form.icon}
                  onChange={e => setForm({ ...form, icon: e.target.value })}
                  placeholder="如 Home / Bot / Shield"
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30"
                />
              </div>
              <div>
                <label className="block text-xs text-ink-400 mb-1">权限码</label>
                <input
                  value={form.permissionCode}
                  onChange={e => setForm({ ...form, permissionCode: e.target.value })}
                  placeholder="如 user:manage（留空表示无限制）"
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30"
                />
              </div>
              <div>
                <label className="block text-xs text-ink-400 mb-1">是否可见</label>
                <select
                  value={form.visible}
                  onChange={e => setForm({ ...form, visible: Number(e.target.value) })}
                  className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30"
                >
                  <option value={1}>可见</option>
                  <option value={0}>隐藏</option>
                </select>
              </div>
            </div>
            <div className="flex justify-end gap-3 mt-4">
              <button onClick={() => setShowModal(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button
                onClick={handleSave}
                disabled={saving || !form.menuName.trim()}
                className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2"
              >
                {saving && <Loader2 className="w-4 h-4 animate-spin" />}
                {editingMenu ? '保存' : '创建'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
