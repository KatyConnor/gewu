'use client';
import { useState, useEffect, useCallback } from 'react';
import { Boxes, Plus, Search, Loader2, X, Pencil, Trash2, Server, ExternalLink } from 'lucide-react';
import { useToast } from '@/components/ui/Toast';
import { confirmDialog } from '@/components/ui/ConfirmDialog';
import Modal from '@/components/ui/Modal';
import { listOrgTree, type OrgDTO } from '@/lib/org';
import {
  listAppSystems, createAppSystem, updateAppSystem, deleteAppSystem,
  listAppServices, createAppService, updateAppService, deleteAppService,
  SYSTEM_TYPE_OPTIONS, SERVICE_TYPE_OPTIONS, CRITICALITY_OPTIONS,
  systemTypeLabel, serviceTypeLabel, criticalityLabel,
  type AppSystemDTO, type AppServiceDTO,
} from '@/lib/appsystem';

/** 系统表单状态（systemId 为空 = 新建） */
interface SystemForm {
  systemId: string;
  systemCode: string;
  systemName: string;
  shortName: string;
  systemType: string;
  orgId: string;
  ownerName: string;
  ownerContact: string;
  repoUrl: string;
  criticality: number;
  status: number;
  description: string;
  sortOrder: number;
}

const EMPTY_SYSTEM_FORM: SystemForm = {
  systemId: '', systemCode: '', systemName: '', shortName: '', systemType: 'BUSINESS',
  orgId: '', ownerName: '', ownerContact: '', repoUrl: '',
  criticality: 3, status: 1, description: '', sortOrder: 0,
};

/** 服务表单状态（serviceId 为空 = 新建） */
interface ServiceForm {
  serviceId: string;
  serviceCode: string;
  serviceName: string;
  serviceType: string;
  baseUrl: string;
  healthPath: string;
  version: string;
  ownerName: string;
  status: number;
  description: string;
  sortOrder: number;
}

const EMPTY_SERVICE_FORM: ServiceForm = {
  serviceId: '', serviceCode: '', serviceName: '', serviceType: 'BACKEND',
  baseUrl: '', healthPath: '', version: '', ownerName: '',
  status: 1, description: '', sortOrder: 0,
};

export default function AppSystemManagePage() {
  const toast = useToast();
  const [systems, setSystems] = useState<AppSystemDTO[]>([]);
  const [orgs, setOrgs] = useState<OrgDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [selected, setSelected] = useState<AppSystemDTO | null>(null);
  const [services, setServices] = useState<AppServiceDTO[]>([]);
  const [servicesLoading, setServicesLoading] = useState(false);

  const [systemModal, setSystemModal] = useState<SystemForm | null>(null);
  const [serviceModal, setServiceModal] = useState<ServiceForm | null>(null);
  const [saving, setSaving] = useState(false);

  const loadSystems = useCallback(async (kw?: string) => {
    setLoading(true);
    try {
      const data = await listAppSystems(kw);
      setSystems(data);
      setSelected(prev => (prev ? data.find(s => s.systemId === prev.systemId) || null : null));
    } catch (e) {
      console.error('加载应用系统失败:', e);
      toast('加载失败，可能无应用系统管理权限', 'error');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  const loadServices = useCallback(async (systemId: string) => {
    setServicesLoading(true);
    try {
      setServices(await listAppServices(systemId));
    } catch (e) {
      console.error('加载子模块服务失败:', e);
      setServices([]);
    } finally {
      setServicesLoading(false);
    }
  }, []);

  useEffect(() => {
    loadSystems();
    listOrgTree().then(setOrgs).catch(() => {});
  }, [loadSystems]);

  // 选中系统变化时刷新其服务列表
  useEffect(() => {
    if (selected) loadServices(selected.systemId);
    else setServices([]);
  }, [selected, loadServices]);

  const handleSearch = () => loadSystems(keyword);

  const confirmDeleteSystem = async (system: AppSystemDTO) => {
    if (!(await confirmDialog({ message: `确认删除应用系统「${system.systemName}」？${system.serviceCount > 0 ? `其下 ${system.serviceCount} 个子模块服务需先删除。` : ''}`, danger: true }))) return;
    try {
      await deleteAppSystem(system.systemId);
      toast('应用系统已删除', 'success');
      loadSystems(keyword);
    } catch (e) {
      toast('删除失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const confirmDeleteService = async (service: AppServiceDTO) => {
    if (!selected) return;
    if (!(await confirmDialog({ message: `确认删除服务「${service.serviceName}」？`, danger: true }))) return;
    try {
      await deleteAppService(selected.systemId, service.serviceId);
      toast('子模块服务已删除', 'success');
      loadSystems(keyword);
    } catch (e) {
      toast('删除失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    }
  };

  const handleSaveSystem = async () => {
    if (!systemModal) return;
    if (!systemModal.systemCode.trim() || !systemModal.systemName.trim()) {
      toast('请填写系统编码与名称', 'error'); return;
    }
    if (!systemModal.ownerName.trim()) { toast('请填写技术负责人', 'error'); return; }
    setSaving(true);
    try {
      const payload = {
        systemName: systemModal.systemName.trim(),
        shortName: systemModal.shortName.trim() || undefined,
        systemType: systemModal.systemType,
        orgId: systemModal.orgId || null,
        ownerName: systemModal.ownerName.trim(),
        ownerContact: systemModal.ownerContact.trim() || undefined,
        repoUrl: systemModal.repoUrl.trim() || undefined,
        criticality: systemModal.criticality,
        status: systemModal.status,
        description: systemModal.description.trim() || undefined,
        sortOrder: systemModal.sortOrder,
      };
      if (systemModal.systemId) {
        await updateAppSystem(systemModal.systemId, payload);
        toast('应用系统已更新', 'success');
      } else {
        if (!/^[A-Za-z0-9_-]+$/.test(systemModal.systemCode.trim())) {
          toast('系统编码仅允许字母/数字/下划线/中划线', 'error'); return;
        }
        await createAppSystem({ ...payload, systemCode: systemModal.systemCode.trim() });
        toast('应用系统已创建', 'success');
      }
      setSystemModal(null);
      await loadSystems(keyword);
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleSaveService = async () => {
    if (!serviceModal || !selected) return;
    if (!serviceModal.serviceCode.trim() || !serviceModal.serviceName.trim()) {
      toast('请填写服务编码与名称', 'error'); return;
    }
    if (!serviceModal.ownerName.trim()) { toast('请填写服务负责人', 'error'); return; }
    setSaving(true);
    try {
      const payload = {
        serviceName: serviceModal.serviceName.trim(),
        serviceType: serviceModal.serviceType,
        baseUrl: serviceModal.baseUrl.trim() || undefined,
        healthPath: serviceModal.healthPath.trim() || undefined,
        version: serviceModal.version.trim() || undefined,
        ownerName: serviceModal.ownerName.trim(),
        status: serviceModal.status,
        description: serviceModal.description.trim() || undefined,
        sortOrder: serviceModal.sortOrder,
      };
      if (serviceModal.serviceId) {
        await updateAppService(selected.systemId, serviceModal.serviceId, payload);
        toast('子模块服务已更新', 'success');
      } else {
        if (!/^[A-Za-z0-9_-]+$/.test(serviceModal.serviceCode.trim())) {
          toast('服务编码仅允许字母/数字/下划线/中划线', 'error'); return;
        }
        await createAppService(selected.systemId, { ...payload, serviceCode: serviceModal.serviceCode.trim() });
        toast('子模块服务已创建', 'success');
      }
      setServiceModal(null);
      await loadServices(selected.systemId);
      loadSystems(keyword);
    } catch (e) {
      toast('保存失败: ' + (e instanceof Error ? e.message : String(e)), 'error');
    } finally {
      setSaving(false);
    }
  };

  // 扁平化机构树（用于所属机构下拉）
  const flatOrgs: { id: string; label: string; depth: number }[] = [];
  const collectFlat = (items: OrgDTO[], depth: number) => {
    items.forEach(o => {
      flatOrgs.push({ id: o.orgId, label: o.orgName, depth });
      collectFlat(o.children || [], depth + 1);
    });
  };
  collectFlat(orgs, 0);

  const openCreateSystem = () => setSystemModal({ ...EMPTY_SYSTEM_FORM });
  const openEditSystem = (s: AppSystemDTO) => setSystemModal({
    systemId: s.systemId, systemCode: s.systemCode, systemName: s.systemName,
    shortName: s.shortName || '', systemType: s.systemType, orgId: s.orgId || '',
    ownerName: s.ownerName, ownerContact: s.ownerContact || '', repoUrl: s.repoUrl || '',
    criticality: s.criticality, status: s.status, description: s.description || '', sortOrder: s.sortOrder,
  });
  const openCreateService = () => setServiceModal({ ...EMPTY_SERVICE_FORM });
  const openEditService = (sv: AppServiceDTO) => setServiceModal({
    serviceId: sv.serviceId, serviceCode: sv.serviceCode, serviceName: sv.serviceName,
    serviceType: sv.serviceType, baseUrl: sv.baseUrl || '', healthPath: sv.healthPath || '',
    version: sv.version || '', ownerName: sv.ownerName, status: sv.status,
    description: sv.description || '', sortOrder: sv.sortOrder,
  });

  const inputCls = 'w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30';
  const labelCls = 'block text-xs text-ink-400 mb-1';

  return (
    <div>
      <header className="mb-6">
        <h1 className="text-2xl font-semibold text-ink-50 flex items-center gap-2"><Boxes className="w-6 h-6 text-tech-400" />应用系统</h1>
        <p className="text-ink-400 text-sm mt-1">管理应用系统台账及其子模块服务登记</p>
      </header>

      <div className="flex items-center gap-3 mb-5">
        <div className="flex items-center gap-2 px-3 py-2 bg-ink-800/50 rounded-lg border border-tech-500/10 flex-1 max-w-sm">
          <Search className="w-4 h-4 text-ink-500" />
          <input type="text" value={keyword} onChange={e => setKeyword(e.target.value)} onKeyDown={e => e.key === 'Enter' && handleSearch()} placeholder="搜索系统编码/名称/简称..." className="bg-transparent text-sm text-ink-200 placeholder-ink-500 outline-none flex-1" />
        </div>
        <button onClick={handleSearch} className="px-4 py-2 btn-primary text-white text-sm rounded-lg">搜索</button>
        <button onClick={openCreateSystem} className="ml-auto px-4 py-2 btn-primary text-white text-sm rounded-lg flex items-center gap-1.5"><Plus className="w-4 h-4" />新增应用系统</button>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 text-tech-400 animate-spin" /></div>
      ) : systems.length === 0 ? (
        <div className="text-center py-20 text-ink-500 text-sm">暂无应用系统，点击右上角新增</div>
      ) : (
        <div className="flex gap-5 items-start">
          {/* 左列：系统列表 */}
          <div className="w-[38%] space-y-3 max-h-[calc(100vh-16rem)] overflow-y-auto scrollbar-thin pr-1">
            {systems.map(s => {
              const crit = criticalityLabel(s.criticality);
              const active = selected?.systemId === s.systemId;
              return (
                <button key={s.systemId} onClick={() => setSelected(s)}
                  className={`w-full text-left glass-dark rounded-xl p-4 border transition-all ${active ? 'border-tech-500/40 bg-tech-500/10' : 'border-transparent hover:border-tech-500/20'}`}>
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="font-medium text-ink-50 truncate">{s.systemName}</div>
                      <div className="text-xs text-ink-500 mt-0.5 flex items-center gap-2 flex-wrap">
                        <span className="px-1.5 py-0.5 bg-tech-500/10 text-tech-400 rounded text-[10px]">{s.systemCode}</span>
                        <span className="text-ink-400">{systemTypeLabel(s.systemType)}</span>
                        <span className={crit.color}>{crit.label}</span>
                      </div>
                    </div>
                    <span className={`text-xs flex-shrink-0 ${s.status === 1 ? 'text-green-400' : 'text-cinnabar-400'}`}>● {s.status === 1 ? '启用' : '停用'}</span>
                  </div>
                  <div className="mt-2.5 text-xs text-ink-500 flex items-center gap-3 flex-wrap">
                    <span className="flex items-center gap-1"><Server className="w-3 h-3" />{s.serviceCount} 个服务</span>
                    {s.orgName && <span className="flex items-center gap-1">{s.orgName}</span>}
                    <span>负责人 {s.ownerName}</span>
                  </div>
                </button>
              );
            })}
          </div>

          {/* 右列：选中系统详情与服务列表 */}
          <div className="flex-1 min-w-0">
            {!selected ? (
              <div className="text-center py-20 text-ink-500 text-sm">← 从左侧选择一个应用系统查看子模块服务</div>
            ) : (
              <>
                <div className="glass-dark rounded-xl p-5 mb-5">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <h2 className="text-lg font-semibold text-ink-50">{selected.systemName}</h2>
                        {selected.shortName && <span className="text-xs text-ink-400">（{selected.shortName}）</span>}
                        <span className={`text-xs ${selected.status === 1 ? 'text-green-400' : 'text-cinnabar-400'}`}>● {selected.status === 1 ? '启用' : '停用'}</span>
                      </div>
                      <p className="text-xs text-ink-500 mt-1">{selected.description || '暂无描述'}</p>
                    </div>
                    <div className="flex items-center gap-2 flex-shrink-0">
                      <button onClick={() => openEditSystem(selected)} className="text-xs px-2.5 py-1.5 rounded-lg border border-tech-500/15 text-ink-300 hover:bg-tech-500/10 flex items-center gap-1"><Pencil className="w-3 h-3" />编辑</button>
                      <button onClick={() => confirmDeleteSystem(selected)} className="text-xs px-2.5 py-1.5 rounded-lg border border-cinnabar-500/20 text-cinnabar-400 hover:bg-cinnabar-500/10 flex items-center gap-1"><Trash2 className="w-3 h-3" />删除</button>
                    </div>
                  </div>
                  <div className="mt-3 grid grid-cols-2 md:grid-cols-3 gap-x-6 gap-y-2 text-xs">
                    <div><span className="text-ink-500">系统编码：</span><span className="text-ink-200">{selected.systemCode}</span></div>
                    <div><span className="text-ink-500">系统分类：</span><span className="text-ink-200">{systemTypeLabel(selected.systemType)}</span></div>
                    <div><span className="text-ink-500">重要等级：</span><span className={criticalityLabel(selected.criticality).color}>{criticalityLabel(selected.criticality).label}</span></div>
                    <div><span className="text-ink-500">所属机构：</span><span className="text-ink-200">{selected.orgName || '-'}</span></div>
                    <div><span className="text-ink-500">技术负责人：</span><span className="text-ink-200">{selected.ownerName}{selected.ownerContact ? `（${selected.ownerContact}）` : ''}</span></div>
                    <div className="truncate">
                      <span className="text-ink-500">代码仓库：</span>
                      {selected.repoUrl ? (
                        <a href={selected.repoUrl} target="_blank" rel="noreferrer" className="text-tech-400 hover:underline inline-flex items-center gap-0.5">{selected.repoUrl}<ExternalLink className="w-3 h-3" /></a>
                      ) : <span className="text-ink-200">-</span>}
                    </div>
                  </div>
                </div>

                <div className="flex items-center justify-between mb-3">
                  <h3 className="text-sm font-medium text-ink-100">子模块服务（{services.length}）</h3>
                  <button onClick={openCreateService} className="text-xs px-3 py-1.5 btn-primary text-white rounded-lg flex items-center gap-1"><Plus className="w-3 h-3" />新增服务</button>
                </div>
                {servicesLoading ? (
                  <div className="flex items-center justify-center py-10"><Loader2 className="w-5 h-5 text-tech-400 animate-spin" /></div>
                ) : services.length === 0 ? (
                  <div className="text-center py-10 text-ink-500 text-sm glass-dark rounded-xl">该系统暂无子模块服务</div>
                ) : (
                  <div className="glass-dark rounded-xl overflow-hidden">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="text-xs text-ink-500 border-b border-tech-500/10">
                          <th className="text-left px-4 py-2.5 font-medium">服务</th>
                          <th className="text-left px-4 py-2.5 font-medium">类型</th>
                          <th className="text-left px-4 py-2.5 font-medium">地址/版本</th>
                          <th className="text-left px-4 py-2.5 font-medium">负责人</th>
                          <th className="text-left px-4 py-2.5 font-medium">状态</th>
                          <th className="text-right px-4 py-2.5 font-medium">操作</th>
                        </tr>
                      </thead>
                      <tbody>
                        {services.map(sv => (
                          <tr key={sv.serviceId} className="border-b border-tech-500/5 hover:bg-tech-500/5">
                            <td className="px-4 py-2.5"><div className="font-medium text-ink-100">{sv.serviceName}</div><div className="text-xs text-ink-500">{sv.serviceCode}</div></td>
                            <td className="px-4 py-2.5"><span className="text-xs px-1.5 py-0.5 bg-tech-500/10 text-tech-400 rounded">{serviceTypeLabel(sv.serviceType)}</span></td>
                            <td className="px-4 py-2.5 text-xs text-ink-300"><div className="truncate max-w-[220px]">{sv.baseUrl || '-'}</div><div className="text-ink-500">{sv.version ? `v${sv.version}` : ''}</div></td>
                            <td className="px-4 py-2.5 text-ink-300 text-xs">{sv.ownerName}</td>
                            <td className="px-4 py-2.5"><span className={`text-xs ${sv.status === 1 ? 'text-green-400' : 'text-cinnabar-400'}`}>● {sv.status === 1 ? '启用' : '停用'}</span></td>
                            <td className="px-4 py-2.5 text-right">
                              <div className="flex items-center justify-end gap-2">
                                <button onClick={() => openEditService(sv)} className="text-xs px-2 py-1 rounded border border-tech-500/15 text-ink-300 hover:bg-tech-500/10">编辑</button>
                                <button onClick={() => confirmDeleteService(sv)} className="text-xs px-2 py-1 rounded border border-cinnabar-500/20 text-cinnabar-400 hover:bg-cinnabar-500/10">删除</button>
                              </div>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      )}

      {/* 应用系统表单弹窗 */}
      {systemModal && (
        <Modal open onClose={() => setSystemModal(null)} panelClassName="glass-dark rounded-2xl w-full max-w-lg p-6 shadow-2xl border border-tech-500/10 max-h-[90vh] overflow-y-auto scrollbar-thin">
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-semibold text-ink-50">{systemModal.systemId ? '编辑应用系统' : '新增应用系统'}</h3>
              <button onClick={() => setSystemModal(null)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
            </div>
            <div className="space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={labelCls}>系统编码 *{systemModal.systemId ? '（不可修改）' : ''}</label>
                  <input value={systemModal.systemCode} disabled={!!systemModal.systemId} onChange={e => setSystemModal({ ...systemModal, systemCode: e.target.value })} placeholder="如 gewu-platform" className={`${inputCls} disabled:opacity-50`} />
                </div>
                <div>
                  <label className={labelCls}>系统名称 *</label>
                  <input value={systemModal.systemName} onChange={e => setSystemModal({ ...systemModal, systemName: e.target.value })} placeholder="系统全称" className={inputCls} />
                </div>
              </div>
              <div className="grid grid-cols-3 gap-3">
                <div>
                  <label className={labelCls}>系统简称</label>
                  <input value={systemModal.shortName} onChange={e => setSystemModal({ ...systemModal, shortName: e.target.value })} placeholder="展示用" className={inputCls} />
                </div>
                <div>
                  <label className={labelCls}>系统分类 *</label>
                  <select value={systemModal.systemType} onChange={e => setSystemModal({ ...systemModal, systemType: e.target.value })} className={inputCls}>
                    {SYSTEM_TYPE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                </div>
                <div>
                  <label className={labelCls}>重要等级 *</label>
                  <select value={systemModal.criticality} onChange={e => setSystemModal({ ...systemModal, criticality: Number(e.target.value) })} className={inputCls}>
                    {CRITICALITY_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={labelCls}>技术负责人 *</label>
                  <input value={systemModal.ownerName} onChange={e => setSystemModal({ ...systemModal, ownerName: e.target.value })} placeholder="姓名" className={inputCls} />
                </div>
                <div>
                  <label className={labelCls}>联系方式</label>
                  <input value={systemModal.ownerContact} onChange={e => setSystemModal({ ...systemModal, ownerContact: e.target.value })} placeholder="电话/邮箱/工号" className={inputCls} />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={labelCls}>所属机构</label>
                  <select value={systemModal.orgId} onChange={e => setSystemModal({ ...systemModal, orgId: e.target.value })} className={inputCls}>
                    <option value="">（未分配）</option>
                    {flatOrgs.map(o => <option key={o.id} value={o.id}>{'　'.repeat(o.depth)}{o.label}</option>)}
                  </select>
                </div>
                <div>
                  <label className={labelCls}>运行状态</label>
                  <select value={systemModal.status} onChange={e => setSystemModal({ ...systemModal, status: Number(e.target.value) })} className={inputCls}>
                    <option value={1}>启用</option>
                    <option value={2}>停用</option>
                  </select>
                </div>
              </div>
              <div>
                <label className={labelCls}>代码仓库地址</label>
                <input value={systemModal.repoUrl} onChange={e => setSystemModal({ ...systemModal, repoUrl: e.target.value })} placeholder="https://..." className={inputCls} />
              </div>
              <div>
                <label className={labelCls}>系统描述</label>
                <textarea value={systemModal.description} onChange={e => setSystemModal({ ...systemModal, description: e.target.value })} rows={2} placeholder="系统定位与边界简述" className={`${inputCls} resize-none`} />
              </div>
            </div>
            <div className="flex justify-end gap-3 mt-5">
              <button onClick={() => setSystemModal(null)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleSaveSystem} disabled={saving} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2">{saving && <Loader2 className="w-4 h-4 animate-spin" />}保存</button>
            </div>
        </Modal>
      )}

      {/* 子模块服务表单弹窗 */}
      {serviceModal && (
        <Modal open onClose={() => setServiceModal(null)} panelClassName="glass-dark rounded-2xl w-full max-w-lg p-6 shadow-2xl border border-tech-500/10 max-h-[90vh] overflow-y-auto scrollbar-thin">
            <div className="flex items-center justify-between mb-4">
              <h3 className="text-lg font-semibold text-ink-50">{serviceModal.serviceId ? '编辑子模块服务' : '新增子模块服务'}{selected ? ` - ${selected.systemName}` : ''}</h3>
              <button onClick={() => setServiceModal(null)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button>
            </div>
            <div className="space-y-3">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={labelCls}>服务编码 *{serviceModal.serviceId ? '（不可修改）' : ''}</label>
                  <input value={serviceModal.serviceCode} disabled={!!serviceModal.serviceId} onChange={e => setServiceModal({ ...serviceModal, serviceCode: e.target.value })} placeholder="如 gateway-service" className={`${inputCls} disabled:opacity-50`} />
                </div>
                <div>
                  <label className={labelCls}>服务名称 *</label>
                  <input value={serviceModal.serviceName} onChange={e => setServiceModal({ ...serviceModal, serviceName: e.target.value })} placeholder="服务名称" className={inputCls} />
                </div>
              </div>
              <div className="grid grid-cols-3 gap-3">
                <div>
                  <label className={labelCls}>服务类型 *</label>
                  <select value={serviceModal.serviceType} onChange={e => setServiceModal({ ...serviceModal, serviceType: e.target.value })} className={inputCls}>
                    {SERVICE_TYPE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                </div>
                <div>
                  <label className={labelCls}>当前版本</label>
                  <input value={serviceModal.version} onChange={e => setServiceModal({ ...serviceModal, version: e.target.value })} placeholder="如 1.2.0" className={inputCls} />
                </div>
                <div>
                  <label className={labelCls}>负责人 *</label>
                  <input value={serviceModal.ownerName} onChange={e => setServiceModal({ ...serviceModal, ownerName: e.target.value })} placeholder="姓名" className={inputCls} />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={labelCls}>服务地址</label>
                  <input value={serviceModal.baseUrl} onChange={e => setServiceModal({ ...serviceModal, baseUrl: e.target.value })} placeholder="http://host:port" className={inputCls} />
                </div>
                <div>
                  <label className={labelCls}>健康检查路径</label>
                  <input value={serviceModal.healthPath} onChange={e => setServiceModal({ ...serviceModal, healthPath: e.target.value })} placeholder="如 /actuator/health" className={inputCls} />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className={labelCls}>运行状态</label>
                  <select value={serviceModal.status} onChange={e => setServiceModal({ ...serviceModal, status: Number(e.target.value) })} className={inputCls}>
                    <option value={1}>启用</option>
                    <option value={2}>停用</option>
                  </select>
                </div>
                <div>
                  <label className={labelCls}>排序号</label>
                  <input type="number" value={serviceModal.sortOrder} onChange={e => setServiceModal({ ...serviceModal, sortOrder: Number(e.target.value) })} className={inputCls} />
                </div>
              </div>
              <div>
                <label className={labelCls}>服务描述</label>
                <textarea value={serviceModal.description} onChange={e => setServiceModal({ ...serviceModal, description: e.target.value })} rows={2} placeholder="服务职责简述" className={`${inputCls} resize-none`} />
              </div>
            </div>
            <div className="flex justify-end gap-3 mt-5">
              <button onClick={() => setServiceModal(null)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleSaveService} disabled={saving} className="px-5 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50 flex items-center gap-2">{saving && <Loader2 className="w-4 h-4 animate-spin" />}保存</button>
            </div>
        </Modal>
      )}
    </div>
  );
}
