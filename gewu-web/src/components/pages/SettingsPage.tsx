'use client';
import { useState, useEffect, useCallback } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { RootState } from '@/store';
import { setTheme } from '@/store';
import { ThemeType } from '@/types';
import { useToast } from '@/components/ui/Toast';
import CustomSelect from '@/components/ui/Select';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/Card';
import { Plus, X, Edit3, Eye } from 'lucide-react';
import {
  listProviders, createProvider, updateProvider, toggleProviderStatus, deleteProvider,
  listModels, createModel, updateModel, toggleModelStatus, deleteModel,
  type Provider, type ModelConfig,
} from '@/lib/model-config';
import {
  getMyPreferences, updateMyPreferences, getMyQuota,
  type UserPreference, type QuotaPreflightResult,
} from '@/lib/quota';

const themes: { id: ThemeType; label: string; color: string }[] = [
  { id: 'ink', label: '墨韵', color: '#00b894' },
  { id: 'deepsea', label: '深海', color: '#0ea5e9' },
  { id: 'jade', label: '玉蕴', color: '#0d9488' },
  { id: 'celadon', label: '青瓷', color: '#2ea087' },
];

export default function SettingsPage() {
  const dispatch = useDispatch();
  const toast = useToast();
  const currentTheme = useSelector((s: RootState) => s.app.theme);
  const [activeTab, setActiveTab] = useState('general');
  const [providers, setProviders] = useState<Provider[]>([]);
  const [models, setModels] = useState<ModelConfig[]>([]);
  const [showProviderModal, setShowProviderModal] = useState(false);
  const [showModelModal, setShowModelModal] = useState(false);
  const [providerForm, setProviderForm] = useState({ providerName: '', providerCode: '', baseUrl: '', apiKey: '', description: '', enableImmediately: true });
  const [modelForm, setModelForm] = useState({ providerId: '', modelName: '', modelId: '', modelParams: '', description: '', enableImmediately: true, pricePer1kInput: '', pricePer1kOutput: '', priceUnitTokens: '1000', contextWindowInput: '', contextWindowOutput: '' });
  // 编辑相关状态
  const [editingProvider, setEditingProvider] = useState<Provider | null>(null);
  const [editingModel, setEditingModel] = useState<ModelConfig | null>(null);
  const [viewingProvider, setViewingProvider] = useState<Provider | null>(null);
  const [viewingModel, setViewingModel] = useState<ModelConfig | null>(null);
  const [editProviderForm, setEditProviderForm] = useState({ providerName: '', baseUrl: '', apiKey: '', description: '', logoLetter: '', logoColor: '', textColor: '' });
  const [editModelForm, setEditModelForm] = useState({ providerId: '', modelName: '', modelParams: '', description: '', pricePer1kInput: '', pricePer1kOutput: '', priceUnitTokens: '1000', contextWindowInput: '', contextWindowOutput: '' });
  // 用量偏好（配额提醒阈值/熔断开关）与套餐余量
  const [preference, setPreference] = useState<UserPreference | null>(null);
  const [quotaStatus, setQuotaStatus] = useState<QuotaPreflightResult | null>(null);

  const loadData = useCallback(async () => {
    try {
      const [providerList, modelList] = await Promise.all([listProviders(), listModels()]);
      setProviders(providerList);
      setModels(modelList);
    } catch (err) {
      const msg = err instanceof Error ? err.message : '加载数据失败';
      toast(msg, 'error');
    }
  }, [toast]);

  const loadPreference = useCallback(async () => {
    try {
      const [pref, quota] = await Promise.all([getMyPreferences(), getMyQuota()]);
      setPreference(pref);
      setQuotaStatus(quota);
    } catch { /* 偏好加载失败静默（保留缺省 UI） */ }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);
  useEffect(() => { loadPreference(); }, [loadPreference]);

  const num = (v: string): number | undefined => (v.trim() === '' ? undefined : Number(v));

  const handleCreateProvider = async () => {
    if (!providerForm.providerName || !providerForm.providerCode || !providerForm.baseUrl) {
      toast('请填写必填字段', 'error');
      return;
    }
    try {
      await createProvider({
        providerName: providerForm.providerName,
        providerCode: providerForm.providerCode,
        baseUrl: providerForm.baseUrl,
        apiKey: providerForm.apiKey || undefined,
        description: providerForm.description || undefined,
        enableImmediately: providerForm.enableImmediately,
      });
      toast('供应商添加成功', 'success');
      setShowProviderModal(false);
      setProviderForm({ providerName: '', providerCode: '', baseUrl: '', apiKey: '', description: '', enableImmediately: true });
      loadData();
    } catch (err) {
      toast(err instanceof Error ? err.message : '添加失败', 'error');
    }
  };

  const handleToggleProvider = async (providerId: string) => {
    try {
      await toggleProviderStatus(providerId);
      loadData();
      toast('状态已切换', 'success');
    } catch (err) {
      toast(err instanceof Error ? err.message : '切换失败', 'error');
    }
  };

  const handleDeleteProvider = async (providerId: string) => {
    try {
      await deleteProvider(providerId);
      toast('供应商已删除', 'success');
      loadData();
    } catch (err) {
      toast(err instanceof Error ? err.message : '删除失败', 'error');
    }
  };

  const handleEditProvider = (provider: Provider) => {
    setEditingProvider(provider);
    setEditProviderForm({
      providerName: provider.providerName,
      baseUrl: provider.baseUrl,
      apiKey: '', // 不回显 API Key（安全考虑）
      description: provider.description || '',
      logoLetter: provider.logoLetter || '',
      logoColor: provider.logoColor || '',
      textColor: provider.textColor || '',
    });
  };

  const handleUpdateProvider = async () => {
    if (!editingProvider) return;
    if (!editProviderForm.providerName || !editProviderForm.baseUrl) {
      toast('请填写必填字段', 'error');
      return;
    }
    try {
      await updateProvider(editingProvider.id, {
        providerName: editProviderForm.providerName,
        baseUrl: editProviderForm.baseUrl,
        apiKey: editProviderForm.apiKey || undefined,
        description: editProviderForm.description || undefined,
        logoLetter: editProviderForm.logoLetter || undefined,
        logoColor: editProviderForm.logoColor || undefined,
        textColor: editProviderForm.textColor || undefined,
      });
      toast('供应商更新成功', 'success');
      setEditingProvider(null);
      loadData();
    } catch (err) {
      toast(err instanceof Error ? err.message : '更新失败', 'error');
    }
  };

  const handleCreateModel = async () => {
    if (!modelForm.providerId || !modelForm.modelName || !modelForm.modelId) {
      toast('请填写必填字段', 'error');
      return;
    }
    try {
      await createModel({
        providerId: modelForm.providerId,
        modelName: modelForm.modelName,
        modelId: modelForm.modelId,
        modelParams: modelForm.modelParams || undefined,
        description: modelForm.description || undefined,
        enableImmediately: modelForm.enableImmediately,
        pricePer1kInput: num(modelForm.pricePer1kInput),
        pricePer1kOutput: num(modelForm.pricePer1kOutput),
        priceUnitTokens: num(modelForm.priceUnitTokens),
        contextWindowInput: num(modelForm.contextWindowInput),
        contextWindowOutput: num(modelForm.contextWindowOutput),
      });
      toast('模型添加成功', 'success');
      setShowModelModal(false);
      setModelForm({ providerId: '', modelName: '', modelId: '', modelParams: '', description: '', enableImmediately: true, pricePer1kInput: '', pricePer1kOutput: '', priceUnitTokens: '1000', contextWindowInput: '', contextWindowOutput: '' });
      loadData();
    } catch (err) {
      toast(err instanceof Error ? err.message : '添加失败', 'error');
    }
  };

  const handleToggleModel = async (modelId: string) => {
    try {
      await toggleModelStatus(modelId);
      loadData();
    } catch (err) {
      toast(err instanceof Error ? err.message : '切换失败', 'error');
    }
  };

  const handleDeleteModel = async (modelId: string) => {
    try {
      await deleteModel(modelId);
      toast('模型已删除', 'success');
      loadData();
    } catch (err) {
      toast(err instanceof Error ? err.message : '删除失败', 'error');
    }
  };

  const handleEditModel = (model: ModelConfig) => {
    setEditingModel(model);
    setEditModelForm({
      providerId: model.providerId,
      modelName: model.modelName,
      modelParams: model.modelParams || '',
      description: model.description || '',
      pricePer1kInput: model.pricePer1kInput != null ? String(model.pricePer1kInput) : '',
      pricePer1kOutput: model.pricePer1kOutput != null ? String(model.pricePer1kOutput) : '',
      priceUnitTokens: model.priceUnitTokens != null ? String(model.priceUnitTokens) : '1000',
      contextWindowInput: model.contextWindowInput != null ? String(model.contextWindowInput) : '',
      contextWindowOutput: model.contextWindowOutput != null ? String(model.contextWindowOutput) : '',
    });
  };

  const handleUpdateModel = async () => {
    if (!editingModel) return;
    if (!editModelForm.modelName) {
      toast('请填写模型名称', 'error');
      return;
    }
    try {
      await updateModel(editingModel.id, {
        providerId: editModelForm.providerId || undefined,
        modelName: editModelForm.modelName,
        modelParams: editModelForm.modelParams || undefined,
        description: editModelForm.description || undefined,
        pricePer1kInput: num(editModelForm.pricePer1kInput),
        pricePer1kOutput: num(editModelForm.pricePer1kOutput),
        priceUnitTokens: num(editModelForm.priceUnitTokens),
        contextWindowInput: num(editModelForm.contextWindowInput),
        contextWindowOutput: num(editModelForm.contextWindowOutput),
      });
      toast('模型更新成功', 'success');
      setEditingModel(null);
      loadData();
    } catch (err) {
      toast(err instanceof Error ? err.message : '更新失败', 'error');
    }
  };

  return (
    <div>
      <header className="mb-8">
        <h1 className="text-2xl font-semibold text-ink-50">设置</h1>
        <p className="text-ink-400 text-sm mt-1">管理您的账户和平台偏好</p>
      </header>

      {/* 选项卡 */}
      <div className="flex gap-1 p-1 bg-ink-800/30 rounded-lg mb-8 w-fit">
        {[
          { id: 'general', label: '通用' },
          { id: 'security', label: '安全' },
          { id: 'notification', label: '通知' },
          { id: 'model', label: '模型' },
          { id: 'usage-pref', label: '用量与配额' },
        ].map(tab => (
          <button key={tab.id} onClick={() => setActiveTab(tab.id)}
            className={`px-4 py-2 text-sm rounded-md font-medium transition-all ${activeTab === tab.id ? 'bg-tech-500/15 text-tech-400' : 'text-ink-400 hover:text-ink-200'}`}>
            {tab.label}
          </button>
        ))}
      </div>

      {/* 通用设置 */}
      {activeTab === 'general' && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>个人信息</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="flex items-center gap-4 mb-6">
                <div className="w-16 h-16 rounded-full bg-gradient-to-br from-tech-400 to-tech-600 flex items-center justify-center text-white font-bold text-xl">张</div>
                <div><button className="px-3 py-1.5 text-xs btn-primary text-white rounded-lg">更换头像</button><p className="text-xs text-ink-500 mt-1">支持 JPG、PNG，最大 2MB</p></div>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div><label className="block text-xs text-ink-400 mb-1">姓名</label><input type="text" placeholder="请输入姓名" className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">邮箱</label><input type="email" defaultValue="zhang@company.com" className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">部门</label><input type="text" defaultValue="产品部" className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">职位</label><input type="text" defaultValue="高级总监" className="w-full px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
              </div>
              <div className="mt-4 flex justify-end"><button className="px-4 py-2 btn-primary text-white text-sm rounded-lg">保存修改</button></div>
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>外观设置</CardTitle>
            </CardHeader>
            <CardContent className="space-y-5">
              <div className="flex items-center justify-between">
                <div><p className="text-sm text-ink-100">深色模式</p><p className="text-xs text-ink-500">切换平台主题外观</p></div>
                <div className="w-10 h-6 bg-tech-500/30 rounded-full relative cursor-pointer"><div className="w-4 h-4 bg-tech-400 rounded-full absolute top-1 right-1" /></div>
              </div>
              <div className="flex items-center justify-between">
                <div><p className="text-sm text-ink-100">紧凑模式</p><p className="text-xs text-ink-500">减小间距以显示更多内容</p></div>
                <div className="w-10 h-6 bg-ink-700 rounded-full relative cursor-pointer"><div className="w-4 h-4 bg-ink-500 rounded-full absolute top-1 left-1" /></div>
              </div>
              <div className="flex items-center justify-between">
                <div><p className="text-sm text-ink-100">动画效果</p><p className="text-xs text-ink-500">启用页面过渡动画</p></div>
                <div className="w-10 h-6 bg-tech-500/30 rounded-full relative cursor-pointer"><div className="w-4 h-4 bg-tech-400 rounded-full absolute top-1 right-1" /></div>
              </div>
              <div className="border-t border-glass-border pt-4"><label className="block text-xs text-ink-400 mb-2">配色方案</label><div className="flex gap-2 flex-wrap">{themes.map(t => <button key={t.id} onClick={() => { dispatch(setTheme(t.id)); toast('主题已切换', 'success'); }} className={`w-8 h-8 rounded-full ring-2 transition-all ${currentTheme === t.id ? 'ring-offset-2 ring-offset-ink-900 ring-white/50' : 'ring-transparent hover:ring-white/20'}`} style={{ background: t.color }} title={t.label} />)}</div></div>
              <div className="border-t border-glass-border pt-4"><label className="block text-xs text-ink-400 mb-2">主题</label><div className="grid grid-cols-3 gap-2">
                <button className="flex items-center gap-2 p-2.5 rounded-lg border border-tech-500/30 bg-tech-500/10"><div className="w-4 h-4 rounded-full bg-[#081211] border border-tech-500/30" /><span className="text-xs text-ink-100">深色</span></button>
                <button className="flex items-center gap-2 p-2.5 rounded-lg border border-transparent hover:border-tech-500/15"><div className="w-4 h-4 rounded-full bg-[#f8fafc] border border-ink-600/20" /><span className="text-xs text-ink-300">浅色</span></button>
                <button className="flex items-center gap-2 p-2.5 rounded-lg border border-transparent hover:border-tech-500/15"><div className="w-4 h-4 rounded-full bg-gradient-to-br from-[#081211] to-[#f8fafc] border border-ink-600/20" /><span className="text-xs text-ink-300">跟随系统</span></button>
              </div></div>
              <div className="border-t border-glass-border pt-4"><label className="block text-xs text-ink-400 mb-2">界面字体</label><CustomSelect value="noto" onChange={() => {}} options={[{ value: 'noto', label: 'Noto Sans SC（默认）' }, { value: 'inter', label: 'Inter' }, { value: 'pingfang', label: 'PingFang SC' }, { value: 'yahei', label: 'Microsoft YaHei' }, { value: 'sourcehan', label: 'Source Han Sans' }]} /></div>
              <div className="border-t border-glass-border pt-4"><label className="block text-xs text-ink-400 mb-2">代码字体</label><CustomSelect value="jetbrains" onChange={() => {}} options={[{ value: 'jetbrains', label: 'JetBrains Mono（默认）' }, { value: 'fira', label: 'Fira Code' }, { value: 'sourcecode', label: 'Source Code Pro' }, { value: 'ibmplex', label: 'IBM Plex Mono' }, { value: 'roboto', label: 'Roboto Mono' }]} /></div>
            </CardContent>
          </Card>
        </div>
      )}

      {/* 安全设置 */}
      {activeTab === 'security' && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>安全设置</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex items-center justify-between">
                <div><p className="text-sm text-ink-100">两步验证</p><p className="text-xs text-ink-500">登录时需要额外的验证码</p></div>
                <div className="w-10 h-6 bg-tech-500/30 rounded-full relative cursor-pointer"><div className="w-4 h-4 bg-tech-400 rounded-full absolute top-1 right-1" /></div>
              </div>
              <div className="flex items-center justify-between">
                <div><p className="text-sm text-ink-100">登录通知</p><p className="text-xs text-ink-500">新设备登录时发送通知</p></div>
                <div className="w-10 h-6 bg-tech-500/30 rounded-full relative cursor-pointer"><div className="w-4 h-4 bg-tech-400 rounded-full absolute top-1 right-1" /></div>
              </div>
              <div className="flex items-center justify-between">
                <div><p className="text-sm text-ink-100">API 密钥</p><p className="text-xs text-ink-500">管理您的 API 访问密钥</p></div>
                <button className="px-3 py-1.5 text-xs border border-tech-500/20 text-tech-400 rounded-lg hover:bg-tech-500/10">管理密钥</button>
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>系统通知</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="w-8 h-8 rounded-lg bg-tech-500/10 flex items-center justify-center"><svg className="w-4 h-4 text-tech-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" /></svg></div>
                  <div><p className="text-sm text-ink-100">智能体</p><p className="text-xs text-ink-500">当智能体完成或需要注意时显示系统通知</p></div>
                </div>
                <div className="w-10 h-6 bg-tech-500/30 rounded-full relative cursor-pointer"><div className="w-4 h-4 bg-tech-400 rounded-full absolute top-1 right-1" /></div>
              </div>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="w-8 h-8 rounded-lg bg-gold-500/10 flex items-center justify-center"><svg className="w-4 h-4 text-gold-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" /></svg></div>
                  <div><p className="text-sm text-ink-100">权限</p><p className="text-xs text-ink-500">当需要权限时显示系统通知</p></div>
                </div>
                <div className="w-10 h-6 bg-tech-500/30 rounded-full relative cursor-pointer"><div className="w-4 h-4 bg-tech-400 rounded-full absolute top-1 right-1" /></div>
              </div>
              <div className="flex items-center justify-between"><div className="flex items-center gap-3"><div className="w-8 h-8 rounded-lg bg-cinnabar-500/10 flex items-center justify-center"><svg className="w-4 h-4 text-cinnabar-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.5" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" /></svg></div><div><p className="text-sm text-ink-100">错误</p><p className="text-xs text-ink-500">发生错误时播放声音</p></div></div><div className="w-10 h-6 bg-tech-500/30 rounded-full relative cursor-pointer"><div className="w-4 h-4 bg-tech-400 rounded-full absolute top-1 right-1" /></div></div>
            </CardContent>
          </Card>
        </div>
      )}

      {/* 通知设置 */}
      {activeTab === 'notification' && (
        <Card>
          <CardHeader>
            <CardTitle>通知设置</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center justify-between"><div><p className="text-sm text-ink-100">邮件通知</p><p className="text-xs text-ink-500">接收重要更新的邮件</p></div><div className="w-10 h-6 bg-tech-500/30 rounded-full relative cursor-pointer"><div className="w-4 h-4 bg-tech-400 rounded-full absolute top-1 right-1" /></div></div>
            <div className="flex items-center justify-between"><div><p className="text-sm text-ink-100">浏览器推送</p><p className="text-xs text-ink-500">在浏览器中接收推送通知</p></div><div className="w-10 h-6 bg-ink-700 rounded-full relative cursor-pointer"><div className="w-4 h-4 bg-ink-500 rounded-full absolute top-1 left-1" /></div></div>
          </CardContent>
        </Card>
      )}

      {/* 用量与配额偏好 */}
      {activeTab === 'usage-pref' && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>配额提醒与熔断</CardTitle>
            </CardHeader>
            <CardContent className="space-y-5">
              <div>
                <label className="block text-xs text-ink-400 mb-1">配额提醒阈值（%）</label>
                <div className="flex items-center gap-3">
                  <input type="number" min={10} max={95} value={preference?.quotaAlertThreshold ?? 80}
                    onChange={e => setPreference(prev => prev ? { ...prev, quotaAlertThreshold: Number(e.target.value) } : prev)}
                    className="w-32 px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" />
                  <span className="text-xs text-ink-500">任一时间窗口的消耗达到该比例时提醒一次（10~95）</span>
                </div>
              </div>
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm text-ink-100">配额耗尽后熔断</p>
                  <p className="text-xs text-ink-500">开启后配额耗尽将拒绝新任务；关闭则仅提醒，任务照常执行</p>
                </div>
                <button
                  onClick={() => setPreference(prev => prev ? { ...prev, quotaBlockEnabled: !prev.quotaBlockEnabled } : prev)}
                  className={`w-10 h-6 rounded-full relative transition-all ${preference?.quotaBlockEnabled ? 'bg-tech-500/40' : 'bg-ink-700'}`}>
                  <div className={`w-4 h-4 rounded-full absolute top-1 transition-all ${preference?.quotaBlockEnabled ? 'right-1 bg-tech-400' : 'left-1 bg-ink-500'}`} />
                </button>
              </div>
              <div className="flex justify-end">
                <button onClick={async () => {
                  if (!preference) return;
                  try {
                    const saved = await updateMyPreferences({
                      quotaAlertThreshold: preference.quotaAlertThreshold,
                      quotaBlockEnabled: preference.quotaBlockEnabled,
                    });
                    setPreference(saved);
                    toast('用量偏好已保存', 'success');
                  } catch (err) {
                    toast(err instanceof Error ? err.message : '保存失败', 'error');
                  }
                }} className="px-4 py-2 btn-primary text-white text-sm rounded-lg">保存偏好</button>
              </div>
            </CardContent>
          </Card>
          <Card>
            <CardHeader>
              <CardTitle>当前套餐余量</CardTitle>
            </CardHeader>
            <CardContent>
              {quotaStatus?.bound ? (
                <div className="space-y-4">
                  <p className="text-xs text-ink-500">套餐：{quotaStatus.planName}</p>
                  {quotaStatus.windows.map(w => {
                    const percent = Math.min(100, Math.round(w.utilization * 100));
                    const label = ({ FIVE_HOUR: '5 小时', WEEK: '每周', MONTH: '每月', QUARTER: '每季' } as Record<string, string>)[w.windowType] || w.windowType;
                    return (
                      <div key={w.windowType}>
                        <div className="flex items-center justify-between text-xs mb-1">
                          <span className="text-ink-300">{label} · 已用 {w.usedTokens.toLocaleString()} / {w.tokenLimit.toLocaleString()} tokens</span>
                          <span className={percent >= 90 ? 'text-cinnabar-400' : percent >= 70 ? 'text-gold-400' : 'text-ink-500'}>{percent}%</span>
                        </div>
                        <div className="h-2 bg-ink-800/60 rounded-full overflow-hidden">
                          <div className={`h-full rounded-full ${percent >= 90 ? 'bg-cinnabar-500/70' : percent >= 70 ? 'bg-gold-500/70' : 'bg-tech-500/60'}`} style={{ width: `${percent}%` }} />
                        </div>
                      </div>
                    );
                  })}
                </div>
              ) : (
                <p className="text-xs text-ink-500">当前未绑定配额套餐，不限制用量（仍受模型上下文窗口约束）。如需配额请联系管理员。</p>
              )}
            </CardContent>
          </Card>
        </div>
      )}

      {/* 模型设置 */}
      {activeTab === 'model' && (
        <div className="space-y-6">
          <div className="flex items-center justify-between mb-5">
            <h3 className="text-base font-semibold text-ink-50">供应商</h3>
            <button onClick={() => setShowProviderModal(true)} className="flex items-center gap-1.5 px-4 py-2 btn-primary text-white text-sm rounded-lg"><Plus className="w-4 h-4" />新增供应商</button>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {providers.map(p => (
              <div key={p.id} className="glass-dark rounded-xl p-5 card-hover group relative overflow-hidden">
                {/* 顶部装饰条 */}
                <div className={`absolute top-0 left-0 right-0 h-1 bg-gradient-to-r ${p.logoColor || 'from-tech-500/20 to-tech-600/10'}`} />
                <div className="flex items-start justify-between mb-3">
                  <div className={`w-12 h-12 rounded-xl bg-gradient-to-br ${p.logoColor || 'from-tech-500/20 to-tech-600/10'} flex items-center justify-center`}>
                    <span className={`text-lg font-bold ${p.textColor || 'text-tech-400'}`}>{p.logoLetter || p.providerName.charAt(0)}</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${p.status === 1 ? 'bg-green-500/10 text-green-400' : 'bg-ink-700 text-ink-500'}`}>
                      {p.status === 1 ? '已启用' : '已停用'}
                    </span>
                  </div>
                </div>
                <h4 className="text-sm font-semibold text-ink-50 mb-1">{p.providerName}</h4>
                <p className="text-xs text-ink-500 mb-3 line-clamp-2">{p.description}</p>
                <div className="flex flex-wrap gap-1.5 mb-3">
                  {models.filter(m => m.providerId === p.id).slice(0, 3).map(m => (
                    <span key={m.id} className="text-[10px] px-1.5 py-0.5 bg-ink-800/50 text-ink-400 rounded">{m.modelName}</span>
                  ))}
                  {models.filter(m => m.providerId === p.id).length > 3 && <span className="text-[10px] px-1.5 py-0.5 bg-ink-800/50 text-ink-400 rounded">+{models.filter(m => m.providerId === p.id).length - 3}</span>}
                </div>
                <div className="flex items-center justify-between pt-3 border-t border-glass-border">
                  <span className="text-[10px] text-ink-600">{p.modelsCount || models.filter(m => m.providerId === p.id).length} 个模型</span>
                  <div className="flex items-center gap-2">
                    <button onClick={() => setViewingProvider(p)} className="p-1 text-ink-500 hover:text-tech-400 rounded transition-colors" title="查看"><Eye className="w-3.5 h-3.5" /></button>
                    <button onClick={() => handleEditProvider(p)} className="p-1 text-ink-500 hover:text-tech-400 rounded transition-colors" title="编辑"><Edit3 className="w-3.5 h-3.5" /></button>
                    <button onClick={() => handleToggleProvider(p.id)} className={`w-9 h-5 rounded-full relative transition-all ${p.status === 1 ? 'bg-tech-500/40' : 'bg-ink-700'}`}>
                      <div className={`w-3.5 h-3.5 rounded-full absolute top-0.5 transition-all ${p.status === 1 ? 'right-0.5 bg-tech-400' : 'left-0.5 bg-ink-500'}`} />
                    </button>
                    <button onClick={() => handleDeleteProvider(p.id)} className="p-1 text-ink-600 hover:text-cinnabar-400 rounded transition-colors"><svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg></button>
                  </div>
                </div>
              </div>
            ))}
          </div>
          <Card>
            <CardHeader>
              <CardTitle>模型</CardTitle>
              <div className="flex items-center gap-3">
                <CustomSelect value="all" onChange={() => {}} className="w-24" options={[{ value: 'all', label: '全部供应商' }, { value: 'openai', label: 'OpenAI' }, { value: 'anthropic', label: 'Anthropic' }, { value: 'deepseek', label: 'DeepSeek' }]} />
                <button onClick={() => { setModelForm({ ...modelForm, providerId: modelForm.providerId || providers[0]?.id || '' }); setShowModelModal(true); }} className="flex items-center gap-1.5 px-3 py-1.5 text-xs btn-primary text-white rounded-lg"><Plus className="w-3.5 h-3.5" />新增模型</button>
              </div>
            </CardHeader>
            <CardContent>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead><tr className="border-b border-tech-500/8"><th className="text-left py-2 px-3 text-xs text-ink-500 font-medium">模型名称</th><th className="text-left py-2 px-3 text-xs text-ink-500 font-medium">供应商</th><th className="text-left py-2 px-3 text-xs text-ink-500 font-medium">模型ID</th><th className="text-left py-2 px-3 text-xs text-ink-500 font-medium">状态</th><th className="text-right py-2 px-3 text-xs text-ink-500 font-medium">操作</th></tr></thead>
                  <tbody>
                    {models.map(m => (
                      <tr key={m.id} className="border-b border-tech-500/5 hover:bg-ink-800/20 transition-colors">
                        <td className="py-2.5 px-3 text-ink-100">{m.modelName}</td>
                        <td className="py-2.5 px-3 text-ink-300">{m.providerName}</td>
                        <td className="py-2.5 px-3 text-ink-400 text-xs font-mono">{m.modelId}</td>
                        <td className="py-2.5 px-3"><span className={`text-xs flex items-center gap-1 ${m.status === 1 ? 'text-green-400' : 'text-ink-500'}`}><div className={`w-1.5 h-1.5 rounded-full ${m.status === 1 ? 'bg-green-400' : 'bg-ink-600'}`} />{m.status === 1 ? '已启用' : '已停用'}</span></td>
                        <td className="py-2.5 px-3 text-right">
                          <button onClick={() => setViewingModel(m)} className="p-1 text-ink-500 hover:text-tech-400 rounded" title="查看"><Eye className="w-3.5 h-3.5" /></button>
                          <button onClick={() => handleEditModel(m)} className="p-1 text-ink-500 hover:text-tech-400 rounded ml-1" title="编辑"><Edit3 className="w-3.5 h-3.5" /></button>
                          <button onClick={() => handleToggleModel(m.id)} className="p-1 text-ink-500 hover:text-tech-400 rounded ml-1" aria-label="切换状态"><svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" /><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg></button>
                          <button onClick={() => handleDeleteModel(m.id)} className="p-1 text-ink-500 hover:text-cinnabar-400 rounded ml-1" aria-label="删除"><svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg></button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </CardContent>
          </Card>
        </div>
      )}

      {/* 新增供应商弹窗 */}
      {showProviderModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowProviderModal(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-md max-h-[calc(100vh-2rem)] flex flex-col p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5 flex-shrink-0"><h3 className="text-base font-semibold text-ink-50">新增供应商</h3><button onClick={() => setShowProviderModal(false)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button></div>
            <div className="space-y-4 overflow-y-auto min-h-0 flex-1">
              <div><label className="block text-xs text-ink-400 mb-1">供应商名称 <span className="text-cinnabar-400">*</span></label><input type="text" value={providerForm.providerName} onChange={e => setProviderForm({ ...providerForm, providerName: e.target.value })} placeholder="如：OpenAI、Anthropic" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 transition-all" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">供应商编码 <span className="text-cinnabar-400">*</span></label><input type="text" value={providerForm.providerCode} onChange={e => setProviderForm({ ...providerForm, providerCode: e.target.value })} placeholder="如：openai、anthropic" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 transition-all font-mono text-xs" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">Base URL <span className="text-cinnabar-400">*</span></label><input type="text" value={providerForm.baseUrl} onChange={e => setProviderForm({ ...providerForm, baseUrl: e.target.value })} placeholder="https://api.openai.com/v1" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 transition-all font-mono text-xs" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">API Key</label><input type="password" value={providerForm.apiKey} onChange={e => setProviderForm({ ...providerForm, apiKey: e.target.value })} placeholder="sk-..." className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 transition-all font-mono text-xs" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">描述</label><input type="text" value={providerForm.description} onChange={e => setProviderForm({ ...providerForm, description: e.target.value })} placeholder="供应商描述" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 transition-all" /></div>
              <div className="flex items-center gap-2"><input type="checkbox" checked={providerForm.enableImmediately} onChange={e => setProviderForm({ ...providerForm, enableImmediately: e.target.checked })} className="w-4 h-4 rounded accent-tech-500" /><label className="text-sm text-ink-200">立即启用</label></div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => setShowProviderModal(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleCreateProvider} className="px-5 py-2 btn-primary text-white text-sm rounded-lg">保存</button>
            </div>
          </div>
        </div>
      )}

      {/* 新增模型弹窗 */}
      {showModelModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setShowModelModal(false)}>
          <div className="glass-dark rounded-2xl w-full max-w-lg max-h-[calc(100vh-2rem)] flex flex-col p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5 flex-shrink-0"><h3 className="text-base font-semibold text-ink-50">新增模型</h3><button onClick={() => setShowModelModal(false)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button></div>
            <div className="space-y-4 overflow-y-auto min-h-0 flex-1">
              <div><label className="block text-xs text-ink-400 mb-1">供应商 <span className="text-cinnabar-400">*</span></label><CustomSelect value={modelForm.providerId} onChange={(v) => setModelForm({ ...modelForm, providerId: v })} options={providers.map(p => ({ value: p.id, label: p.providerName }))} /></div>
              <div><label className="block text-xs text-ink-400 mb-1">模型名称 <span className="text-cinnabar-400">*</span></label><input type="text" value={modelForm.modelName} onChange={e => setModelForm({ ...modelForm, modelName: e.target.value })} placeholder="如：GPT-4o、Claude 3.5 Sonnet" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 transition-all" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">模型ID <span className="text-cinnabar-400">*</span></label><input type="text" value={modelForm.modelId} onChange={e => setModelForm({ ...modelForm, modelId: e.target.value })} placeholder="如：gpt-4o、claude-3-5-sonnet" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 transition-all font-mono text-xs" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">模型参数 (JSON)</label><textarea rows={3} value={modelForm.modelParams} onChange={e => setModelForm({ ...modelForm, modelParams: e.target.value })} placeholder='{"temperature": 0.7, "max_tokens": 4096}' className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 resize-none font-mono text-xs" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">描述</label><input type="text" value={modelForm.description} onChange={e => setModelForm({ ...modelForm, description: e.target.value })} placeholder="模型描述" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 transition-all" /></div>
              <div className="grid grid-cols-2 gap-3">
                <div><label className="block text-xs text-ink-400 mb-1">输入单价（元）</label><input type="number" value={modelForm.pricePer1kInput} onChange={e => setModelForm({ ...modelForm, pricePer1kInput: e.target.value })} placeholder="0.00" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">输出单价（元）</label><input type="number" value={modelForm.pricePer1kOutput} onChange={e => setModelForm({ ...modelForm, pricePer1kOutput: e.target.value })} placeholder="0.00" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">计价单位（tokens）</label><input type="number" value={modelForm.priceUnitTokens} onChange={e => setModelForm({ ...modelForm, priceUnitTokens: e.target.value })} placeholder="1000 或 1000000" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">上下文输入窗口（tokens）</label><input type="number" value={modelForm.contextWindowInput} onChange={e => setModelForm({ ...modelForm, contextWindowInput: e.target.value })} placeholder="如 128000" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">最大输出（tokens）</label><input type="number" value={modelForm.contextWindowOutput} onChange={e => setModelForm({ ...modelForm, contextWindowOutput: e.target.value })} placeholder="如 16384" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
              </div>
              <p className="text-[11px] text-ink-600">单价按"元 / 每计价单位 tokens"填写（计价单位默认 1000，百万定价填 1000000）；上下文窗口用于单任务自动压缩与输出上限校验。</p>
              <div className="flex items-center gap-2"><input type="checkbox" checked={modelForm.enableImmediately} onChange={e => setModelForm({ ...modelForm, enableImmediately: e.target.checked })} className="w-4 h-4 rounded accent-tech-500" /><label className="text-sm text-ink-200">立即启用</label></div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => setShowModelModal(false)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleCreateModel} className="px-5 py-2 btn-primary text-white text-sm rounded-lg">保存</button>
            </div>
          </div>
        </div>
      )}

      {/* 编辑供应商弹窗 */}
      {editingProvider && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setEditingProvider(null)}>
          <div className="glass-dark rounded-2xl w-full max-w-md max-h-[calc(100vh-2rem)] flex flex-col p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5 flex-shrink-0"><h3 className="text-base font-semibold text-ink-50">编辑供应商</h3><button onClick={() => setEditingProvider(null)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button></div>
            <div className="space-y-4 overflow-y-auto min-h-0 flex-1">
              <div><label className="block text-xs text-ink-400 mb-1">供应商编码</label><input type="text" value={editingProvider.providerCode} disabled className="w-full px-3 py-2.5 bg-ink-800/30 border border-tech-500/10 rounded-lg text-sm text-ink-500 outline-none font-mono text-xs cursor-not-allowed" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">供应商名称 <span className="text-cinnabar-400">*</span></label><input type="text" value={editProviderForm.providerName} onChange={e => setEditProviderForm({ ...editProviderForm, providerName: e.target.value })} className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30 transition-all" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">Base URL <span className="text-cinnabar-400">*</span></label><input type="text" value={editProviderForm.baseUrl} onChange={e => setEditProviderForm({ ...editProviderForm, baseUrl: e.target.value })} className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30 transition-all font-mono text-xs" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">API Key</label><input type="password" value={editProviderForm.apiKey} onChange={e => setEditProviderForm({ ...editProviderForm, apiKey: e.target.value })} placeholder="留空表示不修改" className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 transition-all font-mono text-xs" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">描述</label><input type="text" value={editProviderForm.description} onChange={e => setEditProviderForm({ ...editProviderForm, description: e.target.value })} className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30 transition-all" /></div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => setEditingProvider(null)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleUpdateProvider} className="px-5 py-2 btn-primary text-white text-sm rounded-lg">保存</button>
            </div>
          </div>
        </div>
      )}

      {/* 编辑模型弹窗 */}
      {editingModel && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setEditingModel(null)}>
          <div className="glass-dark rounded-2xl w-full max-w-lg max-h-[calc(100vh-2rem)] flex flex-col p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5 flex-shrink-0"><h3 className="text-base font-semibold text-ink-50">编辑模型</h3><button onClick={() => setEditingModel(null)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button></div>
            <div className="space-y-4 overflow-y-auto min-h-0 flex-1">
              <div><label className="block text-xs text-ink-400 mb-1">模型ID</label><input type="text" value={editingModel.modelId} disabled className="w-full px-3 py-2.5 bg-ink-800/30 border border-tech-500/10 rounded-lg text-sm text-ink-500 outline-none font-mono text-xs cursor-not-allowed" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">供应商</label><CustomSelect value={editModelForm.providerId} onChange={(v) => setEditModelForm({ ...editModelForm, providerId: v })} options={providers.map(p => ({ value: p.id, label: p.providerName }))} /></div>
              <div><label className="block text-xs text-ink-400 mb-1">模型名称 <span className="text-cinnabar-400">*</span></label><input type="text" value={editModelForm.modelName} onChange={e => setEditModelForm({ ...editModelForm, modelName: e.target.value })} className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30 transition-all" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">模型参数 (JSON)</label><textarea rows={3} value={editModelForm.modelParams} onChange={e => setEditModelForm({ ...editModelForm, modelParams: e.target.value })} placeholder='{"temperature": 0.7, "max_tokens": 4096}' className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30 resize-none font-mono text-xs" /></div>
              <div><label className="block text-xs text-ink-400 mb-1">描述</label><input type="text" value={editModelForm.description} onChange={e => setEditModelForm({ ...editModelForm, description: e.target.value })} className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30 transition-all" /></div>
              <div className="grid grid-cols-2 gap-3">
                <div><label className="block text-xs text-ink-400 mb-1">输入单价（元）</label><input type="number" value={editModelForm.pricePer1kInput} onChange={e => setEditModelForm({ ...editModelForm, pricePer1kInput: e.target.value })} className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">输出单价（元）</label><input type="number" value={editModelForm.pricePer1kOutput} onChange={e => setEditModelForm({ ...editModelForm, pricePer1kOutput: e.target.value })} className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">计价单位（tokens）</label><input type="number" value={editModelForm.priceUnitTokens} onChange={e => setEditModelForm({ ...editModelForm, priceUnitTokens: e.target.value })} className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">上下文输入窗口（tokens）</label><input type="number" value={editModelForm.contextWindowInput} onChange={e => setEditModelForm({ ...editModelForm, contextWindowInput: e.target.value })} className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
                <div><label className="block text-xs text-ink-400 mb-1">最大输出（tokens）</label><input type="number" value={editModelForm.contextWindowOutput} onChange={e => setEditModelForm({ ...editModelForm, contextWindowOutput: e.target.value })} className="w-full px-3 py-2.5 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" /></div>
              </div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => setEditingModel(null)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">取消</button>
              <button onClick={handleUpdateModel} className="px-5 py-2 btn-primary text-white text-sm rounded-lg">保存</button>
            </div>
          </div>
        </div>
      )}

      {/* 查看供应商弹窗 */}
      {viewingProvider && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setViewingProvider(null)}>
          <div className="glass-dark rounded-2xl w-full max-w-md max-h-[calc(100vh-2rem)] flex flex-col p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5 flex-shrink-0"><h3 className="text-base font-semibold text-ink-50">供应商详情</h3><button onClick={() => setViewingProvider(null)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button></div>
            <div className="space-y-3 overflow-y-auto min-h-0 flex-1">
              <div className="flex items-center gap-3 mb-4">
                <div className={`w-12 h-12 rounded-xl bg-gradient-to-br ${viewingProvider.logoColor || 'from-tech-500/20 to-tech-600/10'} flex items-center justify-center`}>
                  <span className={`text-lg font-bold ${viewingProvider.textColor || 'text-tech-400'}`}>{viewingProvider.logoLetter || viewingProvider.providerName.charAt(0)}</span>
                </div>
                <div>
                  <h4 className="text-sm font-semibold text-ink-50">{viewingProvider.providerName}</h4>
                  <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${viewingProvider.status === 1 ? 'bg-green-500/10 text-green-400' : 'bg-ink-700 text-ink-500'}`}>{viewingProvider.status === 1 ? '已启用' : '已停用'}</span>
                </div>
              </div>
              <div className="space-y-2 text-xs">
                <div className="flex justify-between"><span className="text-ink-500">供应商编码</span><span className="text-ink-200 font-mono">{viewingProvider.providerCode}</span></div>
                <div className="flex justify-between"><span className="text-ink-500">Base URL</span><span className="text-ink-200 font-mono text-right" style={{ maxWidth: '250px', wordBreak: 'break-all' }}>{viewingProvider.baseUrl}</span></div>
                <div className="flex justify-between"><span className="text-ink-500">API Key</span><span className="text-ink-200 font-mono">{viewingProvider.baseUrl ? '••••••（已配置）' : '未配置'}</span></div>
                <div className="flex justify-between"><span className="text-ink-500">模型数量</span><span className="text-ink-200">{viewingProvider.modelsCount || models.filter(m => m.providerId === viewingProvider.id).length} 个</span></div>
                <div className="flex justify-between items-start"><span className="text-ink-500 flex-shrink-0">描述</span><span className="text-ink-300 text-right" style={{ maxWidth: '250px' }}>{viewingProvider.description || '无'}</span></div>
              </div>
              <div className="mt-4 pt-3 border-t border-glass-border">
                <p className="text-xs text-ink-500 mb-2">关联模型</p>
                <div className="flex flex-wrap gap-1.5">
                  {models.filter(m => m.providerId === viewingProvider.id).map(m => (
                    <span key={m.id} className="text-[10px] px-1.5 py-0.5 bg-ink-800/50 text-ink-400 rounded">{m.modelName}</span>
                  ))}
                  {models.filter(m => m.providerId === viewingProvider.id).length === 0 && <span className="text-xs text-ink-600">暂无模型</span>}
                </div>
              </div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => setViewingProvider(null)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">关闭</button>
              <button onClick={() => { handleEditProvider(viewingProvider); setViewingProvider(null); }} className="px-5 py-2 btn-primary text-white text-sm rounded-lg">编辑</button>
            </div>
          </div>
        </div>
      )}

      {/* 查看模型弹窗 */}
      {viewingModel && (
        <div className="fixed inset-0 z-50 flex items-center justify-center modal-overlay" onClick={() => setViewingModel(null)}>
          <div className="glass-dark rounded-2xl w-full max-w-md max-h-[calc(100vh-2rem)] flex flex-col p-6 shadow-2xl animate-fade-up border border-tech-500/10" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between mb-5 flex-shrink-0"><h3 className="text-base font-semibold text-ink-50">模型详情</h3><button onClick={() => setViewingModel(null)} className="text-ink-500 hover:text-ink-300"><X className="w-5 h-5" /></button></div>
            <div className="space-y-3 overflow-y-auto min-h-0 flex-1">
              <div className="flex items-center gap-3 mb-4">
                <div className="w-10 h-10 rounded-lg bg-tech-500/10 flex items-center justify-center">
                  <span className="text-sm font-bold text-tech-400">{viewingModel.modelName.charAt(0)}</span>
                </div>
                <div>
                  <h4 className="text-sm font-semibold text-ink-50">{viewingModel.modelName}</h4>
                  <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${viewingModel.status === 1 ? 'bg-green-500/10 text-green-400' : 'bg-ink-700 text-ink-500'}`}>{viewingModel.status === 1 ? '已启用' : '已停用'}</span>
                </div>
              </div>
              <div className="space-y-2 text-xs">
                <div className="flex justify-between"><span className="text-ink-500">模型ID</span><span className="text-ink-200 font-mono">{viewingModel.modelId}</span></div>
                <div className="flex justify-between"><span className="text-ink-500">所属供应商</span><span className="text-ink-200">{viewingModel.providerName}</span></div>
                <div className="flex justify-between"><span className="text-ink-500">供应商编码</span><span className="text-ink-200 font-mono">{viewingModel.providerCode}</span></div>
                <div className="flex justify-between items-start"><span className="text-ink-500 flex-shrink-0">模型参数</span><span className="text-ink-300 font-mono text-right" style={{ maxWidth: '250px', wordBreak: 'break-all' }}>{viewingModel.modelParams || '默认'}</span></div>
                <div className="flex justify-between items-start"><span className="text-ink-500 flex-shrink-0">描述</span><span className="text-ink-300 text-right" style={{ maxWidth: '250px' }}>{viewingModel.description || '无'}</span></div>
              </div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button onClick={() => setViewingModel(null)} className="px-4 py-2 text-sm text-ink-300 hover:text-ink-100">关闭</button>
              <button onClick={() => { handleEditModel(viewingModel); setViewingModel(null); }} className="px-5 py-2 btn-primary text-white text-sm rounded-lg">编辑</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
