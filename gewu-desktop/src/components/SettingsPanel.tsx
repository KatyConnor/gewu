import { useCallback, useEffect, useState } from 'react';
import type { EngineConnection } from '../engine';

/**
 * 统一设置面板（对齐 web 设置页形态）：左侧导航 + 右侧内容区。
 * - 模型设置：与 web 一致的两栏结构——先供应商（编码/显示名/BaseURL/Key），
 *   再供应商下模型清单（模型名/显示名，多模型），密钥经引擎侧 SecretVault 加密；
 * - 子智能体 / MCP 服务器 / 技能库：自 ManagementPanel 平移（端点不变）。
 */
type Section = 'mode' | 'models' | 'agents' | 'mcp' | 'skills' | 'entry';

interface AgentItem { agentId: string; name: string; status: number; modelProvider?: string; modelName?: string; systemPrompt?: string }
interface McpItem { serverId: string; name: string; transport: string; url?: string; command?: string }
interface SkillItem { name: string; preview?: string }
interface ProviderItem {
  code: string; displayName?: string; baseUrl?: string;
  modelsJson?: string; keyConfigured?: boolean;
}
/** 模型行（用户指定扩展）：基础四项 + 高级配置（输入类型/模型能力/推理等级/参数映射），
 *  全部平铺进 modelsJson 由引擎透传（local_model_provider.models_json），旧数据缺省兼容 */
interface ModelRow {
  name: string; displayName: string;
  contextWindow: string; maxOutputTokens: string; // 表单态字符串（空=未设置）
  inputTypes: string[];      // text/image/video/pdf（text 基础模态恒选）
  capabilities: string[];    // structured_output/web_search/system_message
  reasoningLevels: string[]; // low/high/max/自定义（从低到高）
  reasoningParams: string;   // JSON 文本（推理参数映射）
}

const EMPTY_ROW: ModelRow = {
  name: '', displayName: '', contextWindow: '', maxOutputTokens: '',
  inputTypes: ['text'], capabilities: [], reasoningLevels: [], reasoningParams: '',
};

const INPUT_TYPES = [
  { key: 'text', label: '文本' }, { key: 'image', label: '图片' },
  { key: 'video', label: '视频' }, { key: 'pdf', label: 'PDF' },
];
const CAPABILITIES = [
  { key: 'structured_output', label: '结构化输出' },
  { key: 'web_search', label: '原生联网搜索' },
  { key: 'system_message', label: '对话中系统消息' },
];
const REASONING_PRESETS = ['low', 'high', 'max'];

/** 兼容旧 modelsJson（仅 name/displayName）：缺省字段回落空值 */
const parseModelRow = (raw: Record<string, unknown>): ModelRow => ({
  name: typeof raw.name === 'string' ? raw.name : '',
  displayName: typeof raw.displayName === 'string' ? raw.displayName : '',
  contextWindow: raw.contextWindow != null ? String(raw.contextWindow) : '',
  maxOutputTokens: raw.maxOutputTokens != null ? String(raw.maxOutputTokens) : '',
  inputTypes: Array.isArray(raw.inputTypes) && raw.inputTypes.length
    ? (raw.inputTypes as string[]) : ['text'],
  capabilities: Array.isArray(raw.capabilities) ? (raw.capabilities as string[]) : [],
  reasoningLevels: Array.isArray(raw.reasoningLevels) ? (raw.reasoningLevels as string[]) : [],
  reasoningParams: typeof raw.reasoningParams === 'string' ? raw.reasoningParams : '',
});

const SECTION_LABEL: Record<Section, string> = {
  mode: '使用模式', models: '模型设置', agents: '子智能体', mcp: 'MCP 服务器', skills: '技能库',
  entry: '调度入口',
};

export default function SettingsPanel({ conn, initialTab = 'models', onClose, onChanged }: {
  conn: EngineConnection;
  initialTab?: Section;
  onClose: () => void;
  onChanged?: () => void;
}) {
  const [section, setSection] = useState<Section>(initialTab);
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // ===== 数据 =====
  const [agents, setAgents] = useState<AgentItem[]>([]);
  const [mcps, setMcps] = useState<McpItem[]>([]);
  const [skills, setSkills] = useState<SkillItem[]>([]);
  const [mcpTools, setMcpTools] = useState<Record<string, Array<{ name: string; description?: string }>>>({});
  const [providers, setProviders] = useState<ProviderItem[]>([]);
  // 使用模式(本地/格物平台):切换会重建窗口加载对应界面
  const [modeInfo, setModeInfo] = useState<{ mode: string; cloudUrl?: string } | null>(null);
  const [modeSwitching, setModeSwitching] = useState(false);
  const [modeUrl, setModeUrl] = useState('https://');
  const [modeErr, setModeErr] = useState<string | null>(null);

  // ===== 表单 =====
  const [agentForm, setAgentForm] = useState({ name: '', modelProvider: '', modelName: '', systemPrompt: '' });
  const [mcpForm, setMcpForm] = useState({ name: '', transport: 'stdio', command: '', url: '' });
  const [skillForm, setSkillForm] = useState({ name: '', content: '' });

  // ===== 调度入口（AGENTS.md/GEWU.md，设计文档 56 §7.2） =====
  const [entryScope, setEntryScope] = useState<'global' | 'project'>('global');
  const [entryName, setEntryName] = useState<'GEWU.md' | 'AGENTS.md'>('GEWU.md');
  const [entryProject, setEntryProject] = useState('');
  const [entryContent, setEntryContent] = useState('');
  const [entryPath, setEntryPath] = useState('');

  // ===== 模型设置状态：选中供应商 + 编辑表单 =====
  const [selected, setSelected] = useState<string | null>(null); // code 或 '__new__'
  const [provForm, setProvForm] = useState<{ code: string; displayName: string; baseUrl: string; apiKey: string; keyConfigured: boolean }>(
    { code: '', displayName: '', baseUrl: '', apiKey: '', keyConfigured: false });
  const [modelRows, setModelRows] = useState<ModelRow[]>([]);
  // 高级配置折叠态 / 自定义推理等级输入（按行索引）
  const [advOpen, setAdvOpen] = useState<Record<number, boolean>>({});
  const [addingLevel, setAddingLevel] = useState<number | null>(null);
  const [levelDraft, setLevelDraft] = useState('');

  const load = useCallback(async () => {
    try { setAgents((await conn.data('/v1/agents')) as AgentItem[]); } catch { /* 忽略 */ }
    try { setMcps((await conn.data('/v1/mcp-servers')) as McpItem[]); } catch { /* 忽略 */ }
    try { setSkills((await conn.data('/v1/skills')) as SkillItem[]); } catch { /* 忽略 */ }
    try { setProviders((await conn.data('/v1/engine/providers')) as ProviderItem[]); } catch { /* 忽略 */ }
  }, [conn]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    window.gewu.getLaunchConfig().then(setModeInfo).catch(() => {});
  }, []);

  const run = useCallback(async (fn: () => Promise<void>, ok: string) => {
    setBusy(true);
    try { await fn(); setMsg(ok); onChanged?.(); } catch (e) { setMsg(`失败: ${String(e)}`); } finally { setBusy(false); }
  }, [onChanged]);

  const switchToCloud = async () => {
    setModeSwitching(true); setModeErr(null);
    try {
      await window.gewu.saveLaunchConfig({ mode: 'cloud', cloudUrl: modeUrl.trim() });
      // 主进程已重建窗口直接加载平台界面,渲染层随窗口销毁
    } catch (e) {
      setModeErr(e instanceof Error ? e.message : String(e));
      setModeSwitching(false);
    }
  };

  // ==================== 模型设置操作 ====================
  const selectProvider = useCallback((code: string | null) => {
    setMsg(null);
    setSelected(code);
    if (code === null || code === '__new__') {
      setProvForm({ code: '', displayName: '', baseUrl: '', apiKey: '', keyConfigured: false });
      setModelRows([]);
      return;
    }
    const p = providers.find((x) => x.code === code);
    let models: ModelRow[] = [];
    try {
      models = p?.modelsJson
        ? (JSON.parse(p.modelsJson) as Array<Record<string, unknown>>).map(parseModelRow)
        : [];
    } catch { models = []; }
    setProvForm({
      code: p?.code ?? code, displayName: p?.displayName ?? '', baseUrl: p?.baseUrl ?? '',
      apiKey: '', keyConfigured: Boolean(p?.keyConfigured),
    });
    setModelRows(models);
  }, [providers]);

  // 模型行 → modelsJson 元素：新字段平铺写入，空值不写（保持旧形态兼容）
  const serializeModelRows = () => modelRows.filter((m) => m.name.trim()).map((m) => {
    const out: Record<string, unknown> = { name: m.name.trim() };
    if (m.displayName.trim()) out.displayName = m.displayName.trim();
    if (m.contextWindow.trim()) {
      const ctx = Number(m.contextWindow.trim());
      if (!Number.isFinite(ctx) || ctx <= 0) throw new Error(`模型 ${m.name.trim()} 的上下文窗口需为正整数`);
      out.contextWindow = ctx;
    }
    if (m.maxOutputTokens.trim()) {
      const mot = Number(m.maxOutputTokens.trim());
      if (!Number.isFinite(mot) || mot <= 0) throw new Error(`模型 ${m.name.trim()} 的最大输出 Token 需为正整数`);
      out.maxOutputTokens = mot;
    }
    if (m.inputTypes.length) out.inputTypes = m.inputTypes;
    if (m.capabilities.length) out.capabilities = m.capabilities;
    if (m.reasoningLevels.length) out.reasoningLevels = m.reasoningLevels;
    const rp = m.reasoningParams.trim();
    if (rp) {
      try { out.reasoningParams = JSON.parse(rp); } catch {
        throw new Error(`模型 ${m.name.trim()} 的推理参数映射不是合法 JSON`);
      }
    }
    return out;
  });

  const saveProvider = () => run(async () => {
    if (!provForm.code.trim()) throw new Error('编码必填');
    if (modelRows.some((m) => !m.name.trim())) throw new Error('模型清单存在未填名的行');
    await conn.fetch('/v1/engine/providers', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        code: provForm.code.trim(), displayName: provForm.displayName.trim() || provForm.code.trim(),
        baseUrl: provForm.baseUrl.trim(), apiKey: provForm.apiKey.trim() || undefined,
        modelsJson: JSON.stringify(serializeModelRows()),
      }),
    });
    await load();
    setSelected(provForm.code.trim());
    setProvForm((f) => ({ ...f, apiKey: '', keyConfigured: true }));
  }, `供应商 ${provForm.code} 已保存（Key 经 SecretVault 加密，重启自动恢复）`);

  const deleteProvider = () => run(async () => {
    if (!selected || selected === '__new__') throw new Error('未选中供应商');
    await conn.fetch(`/v1/engine/providers/${encodeURIComponent(selected)}`, { method: 'DELETE' });
    await load();
    setSelected(null);
  }, `供应商 ${selected} 已删除`);

  // ==================== 子智能体 / MCP / 技能操作（自 ManagementPanel 平移） ====================
  const createAgent = () => run(async () => {
    if (!agentForm.name) throw new Error('name 必填');
    await conn.fetch('/v1/agents', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(agentForm),
    });
    setAgentForm({ name: '', modelProvider: '', modelName: '', systemPrompt: '' });
    await load();
  }, 'Agent 已创建');

  const deleteAgent = (id: string) => run(async () => {
    await conn.fetch(`/v1/agents/${id}`, { method: 'DELETE' });
    await load();
  }, `Agent ${id} 已删除`);

  const createMcp = () => run(async () => {
    if (!mcpForm.name) throw new Error('name 必填');
    await conn.fetch('/v1/mcp-servers', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(mcpForm),
    });
    setMcpForm({ name: '', transport: 'stdio', command: '', url: '' });
    await load();
  }, 'MCP 服务器已注册');

  const deleteMcp = (id: string) => run(async () => {
    await conn.fetch(`/v1/mcp-servers/${id}`, { method: 'DELETE' });
    await load();
  }, `MCP ${id} 已删除`);

  const discoverTools = (id: string) => run(async () => {
    const tools = (await conn.data(`/v1/mcp-servers/${id}/tools`)) as Array<{ name: string; description?: string }>;
    setMcpTools((prev) => ({ ...prev, [id]: tools }));
  }, '工具发现完成');

  const installSkill = () => run(async () => {
    if (!skillForm.name) throw new Error('name 必填');
    await conn.fetch(`/v1/skills/${encodeURIComponent(skillForm.name)}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: skillForm.content }),
    });
    setSkillForm({ name: '', content: '' });
    await load();
  }, '技能已安装');

  const deleteSkill = (name: string) => run(async () => {
    await conn.fetch(`/v1/skills/${encodeURIComponent(name)}`, { method: 'DELETE' });
    await load();
  }, `技能 ${name} 已卸载`);

  // ==================== 调度入口文件（设计文档 56 §7.2） ====================
  const loadEntry = useCallback(async () => {
    if (section !== 'entry') return;
    if (entryScope === 'project' && !entryProject.trim()) { setEntryContent(''); setEntryPath(''); return; }
    try {
      const qs = `scope=${entryScope}&name=${entryName}`
        + (entryScope === 'project' ? `&project=${encodeURIComponent(entryProject.trim())}` : '');
      const data = (await conn.data(`/v1/entry-file?${qs}`)) as { exists: boolean; content: string; path: string };
      setEntryContent(data.content || '');
      setEntryPath(data.path || '');
    } catch { setMsg('调度入口文件读取失败'); }
  }, [section, entryScope, entryName, entryProject, conn]);

  useEffect(() => { loadEntry(); }, [loadEntry]);

  const saveEntry = () => run(async () => {
    if (entryScope === 'project' && !entryProject.trim()) throw new Error('项目层编辑需填写项目目录');
    await conn.fetch('/v1/entry-file', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        scope: entryScope, name: entryName,
        project: entryScope === 'project' ? entryProject.trim() : undefined,
        content: entryContent,
      }),
    });
    await loadEntry();
  }, `已保存 ${entryName}（${entryScope === 'global' ? '全局 ~/.gewu' : '项目根'}）`);

  // ==================== 渲染辅助 ====================
  const input = (placeholder: string, value: string, onChange: (v: string) => void, type = 'text') => (
    <input placeholder={placeholder} value={value} type={type}
      onChange={(e) => onChange(e.target.value)}
      className="w-full bg-[#0a0f0d] text-[#c9d1d9] border border-[#21262d] rounded-md p-2 text-xs mb-2 outline-none focus:border-[#00d4aa]" />
  );
  const btn = (label: string, onClick: () => void, primary = false, danger = false) => (
    <button className={`btn small ${primary ? 'primary' : ''} ${danger ? 'danger' : ''}`}
      onClick={onClick} disabled={busy}>{label}</button>
  );

  return (
    <div className="overlay" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="settings-shell" onClick={(e) => e.stopPropagation()}>
        {/* ===== 左导航（对齐 web 设置页） ===== */}
        <aside className="settings-nav">
          <div className="settings-nav-head">
            <span className="dialog-title">设置</span>
            <button className="btn small" onClick={onClose}>关闭</button>
          </div>
          {(Object.keys(SECTION_LABEL) as Section[]).map((s) => (
            <div key={s} className={`settings-nav-item ${section === s ? 'active' : ''}`}
              onClick={() => { setSection(s); setMsg(null); }}>
              {SECTION_LABEL[s]}
            </div>
          ))}
        </aside>

        {/* ===== 内容区 ===== */}
        <div className="settings-content">
          <h3 className="settings-title">{SECTION_LABEL[section]}</h3>
          {msg && <p className="hint">{msg}</p>}

          {section === 'mode' && (
            <div className="mode-block">
              <p className="mode-current">
                当前使用模式：
                <b>{modeInfo?.mode === 'cloud' ? '格物平台登录使用' : modeInfo?.mode === 'local' ? '本地离线使用' : '未配置'}</b>
              </p>
              {modeInfo?.cloudUrl && <p className="hint">平台地址：{modeInfo.cloudUrl}</p>}
              {!modeSwitching ? (
                <div className="form-block">
                  <button className="btn primary" onClick={() => setModeSwitching(true)}>切换到格物平台登录使用</button>
                  <p className="hint">平台模式将直接打开格物平台界面（需配置线上访问地址）；可随时通过系统托盘菜单切回本地离线使用。</p>
                </div>
              ) : (
                <div className="form-block">
                  {input('格物平台访问地址（如 https://gewu.example.com）', modeUrl, setModeUrl)}
                  {modeErr && <p className="error">{modeErr}</p>}
                  <div className="dialog-actions" style={{ justifyContent: 'flex-start', marginTop: 8 }}>
                    <button className="btn" onClick={() => setModeSwitching(false)}>取消</button>
                    <button className="btn primary" disabled={!/^https?:\/\//.test(modeUrl.trim())}
                      onClick={switchToCloud}>确认并切换</button>
                  </div>
                </div>
              )}
            </div>
          )}

          {section === 'models' && (
            <div className="prov-wrap">
              {/* 左列：供应商清单 */}
              <div className="prov-list">
                <button className={`prov-item ${selected === '__new__' ? 'active' : ''}`}
                  onClick={() => selectProvider('__new__')}>＋ 新建供应商</button>
                {providers.length === 0 && <p className="hint">尚未配置供应商</p>}
                {providers.map((p) => (
                  <div key={p.code} className={`prov-item ${selected === p.code ? 'active' : ''}`}
                    onClick={() => selectProvider(p.code)}>
                    <span className="prov-item-name">{p.displayName || p.code}</span>
                    <span className="prov-item-code">{p.code}{p.keyConfigured ? ' · 🔑' : ' · 未配 Key'}</span>
                  </div>
                ))}
              </div>
              {/* 右列：编辑表单 + 模型清单 */}
              <div className="prov-edit">
                {!selected ? (
                  <p className="hint">选择左侧供应商编辑，或「＋ 新建供应商」</p>
                ) : (
                  <>
                    {input('编码（如 zhipu，同编码覆盖更新）', provForm.code,
                      (v) => setProvForm((f) => ({ ...f, code: v })))}
                    {input('显示名', provForm.displayName, (v) => setProvForm((f) => ({ ...f, displayName: v })))}
                    {input('Base URL（如 https://open.bigmodel.cn/api/paas/v4）', provForm.baseUrl,
                      (v) => setProvForm((f) => ({ ...f, baseUrl: v })))}
                    {input(provForm.keyConfigured ? 'API Key（已配置，留空保持不变）' : 'API Key', provForm.apiKey,
                      (v) => setProvForm((f) => ({ ...f, apiKey: v })), 'password')}
                    <div className="model-rows-head">
                      <span className="hint">模型清单（该供应商下可配置多个模型）</span>
                      <button className="btn small" disabled={busy}
                        onClick={() => setModelRows((rows) => [...rows, { ...EMPTY_ROW }])}>＋ 添加模型</button>
                    </div>
                    {modelRows.length === 0 && <p className="hint">暂无模型——至少添加一个模型供对话选择</p>}
                    {modelRows.map((m, i) => {
                      const toggleList = (field: 'inputTypes' | 'capabilities', key: string) => {
                        if (field === 'inputTypes' && key === 'text') return; // 基础模态恒选
                        setModelRows((rows) => rows.map((x, j) => {
                          if (j !== i) return x;
                          const has = x[field].includes(key);
                          return { ...x, [field]: has ? x[field].filter((k) => k !== key) : [...x[field], key] };
                        }));
                      };
                      const toggleLevel = (lv: string) => setModelRows((rows) => rows.map((x, j) => {
                        if (j !== i) return x;
                        const has = x.reasoningLevels.includes(lv);
                        return { ...x, reasoningLevels: has
                          ? x.reasoningLevels.filter((k) => k !== lv)
                          : [...x.reasoningLevels, lv] };
                      }));
                      const commitLevel = () => {
                        const v = levelDraft.trim();
                        if (v) toggleLevel(v);
                        setLevelDraft(''); setAddingLevel(null);
                      };
                      const advConfigured = m.inputTypes.length > 1 || m.capabilities.length > 0
                        || m.reasoningLevels.length > 0 || m.reasoningParams.trim().length > 0;
                      return (
                        <div key={i} className="model-row col">
                          <div className="model-grid">
                            <input placeholder="模型 ID（如 glm-4-flash）" value={m.name}
                              onChange={(e) => setModelRows((rows) => rows.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
                            <input placeholder="显示名（可选）" value={m.displayName}
                              onChange={(e) => setModelRows((rows) => rows.map((x, j) => (j === i ? { ...x, displayName: e.target.value } : x)))} />
                            <input placeholder="上下文窗口（如 131072）" inputMode="numeric" value={m.contextWindow}
                              onChange={(e) => setModelRows((rows) => rows.map((x, j) => (j === i ? { ...x, contextWindow: e.target.value.replace(/[^\d]/g, '') } : x)))} />
                            <input placeholder="最大输出 Token（如 8192）" inputMode="numeric" value={m.maxOutputTokens}
                              onChange={(e) => setModelRows((rows) => rows.map((x, j) => (j === i ? { ...x, maxOutputTokens: e.target.value.replace(/[^\d]/g, '') } : x)))} />
                          </div>
                          <button type="button" className="adv-toggle"
                            onClick={() => setAdvOpen((s) => ({ ...s, [i]: !s[i] }))}>
                            <span className={`adv-chev ${advOpen[i] ? 'open' : ''}`}>⌄</span>高级配置
                            {advConfigured && <span className="adv-dot">已配置</span>}
                          </button>
                          {advOpen[i] && (
                            <div className="adv-body">
                              <div className="adv-label">输入类型</div>
                              <div className="chip-row">
                                {INPUT_TYPES.map(({ key, label }) => (
                                  <label key={key}
                                    className={`chip-check ${m.inputTypes.includes(key) ? 'on' : ''} ${key === 'text' ? 'lock' : ''}`}>
                                    <input type="checkbox" disabled={key === 'text'}
                                      checked={m.inputTypes.includes(key)}
                                      onChange={() => toggleList('inputTypes', key)} />{label}
                                  </label>
                                ))}
                              </div>
                              <div className="adv-label">模型能力</div>
                              <div className="chip-row">
                                {CAPABILITIES.map(({ key, label }) => (
                                  <label key={key} className={`chip-check ${m.capabilities.includes(key) ? 'on' : ''}`}>
                                    <input type="checkbox" checked={m.capabilities.includes(key)}
                                      onChange={() => toggleList('capabilities', key)} />{label}
                                  </label>
                                ))}
                              </div>
                              <div className="adv-label">推理等级（从低到高）</div>
                              <div className="chip-row">
                                {[...new Set([...REASONING_PRESETS, ...m.reasoningLevels])].map((lv) => (
                                  <button key={lv} type="button"
                                    className={`chip-check ${m.reasoningLevels.includes(lv) ? 'on' : ''}`}
                                    onClick={() => toggleLevel(lv)}>{lv}</button>
                                ))}
                                {addingLevel === i ? (
                                  <input className="chip-input" autoFocus value={levelDraft}
                                    placeholder="自定义等级"
                                    onChange={(e) => setLevelDraft(e.target.value)}
                                    onKeyDown={(e) => {
                                      if (e.key === 'Enter') commitLevel();
                                      if (e.key === 'Escape') { setAddingLevel(null); setLevelDraft(''); }
                                    }}
                                    onBlur={commitLevel} />
                                ) : (
                                  <button type="button" className="chip-add" title="添加自定义推理等级"
                                    onClick={() => { setAddingLevel(i); setLevelDraft(''); }}>＋</button>
                                )}
                              </div>
                              <div className="adv-label">推理参数映射（JSON，可选）</div>
                              <textarea className="adv-json" rows={4} value={m.reasoningParams}
                                placeholder={'{\n  "thinking": { "type": "enabled" }\n}'}
                                onChange={(e) => setModelRows((rows) => rows.map((x, j) => (j === i ? { ...x, reasoningParams: e.target.value } : x)))} />
                            </div>
                          )}
                          <div className="model-row-actions">
                            <button className="btn small danger" disabled={busy}
                              onClick={() => setModelRows((rows) => rows.filter((_, j) => j !== i))}>删除</button>
                          </div>
                        </div>
                      );
                    })}
                    <div className="dialog-actions" style={{ justifyContent: 'flex-start', marginTop: 10 }}>
                      {btn('保存供应商', saveProvider, true)}
                      {selected !== '__new__' && btn('删除供应商', deleteProvider, false, true)}
                    </div>
                  </>
                )}
              </div>
            </div>
          )}

          {section === 'agents' && (
            <div>
              <div className="list-block">
                {agents.length === 0 && <p className="hint">暂无 Agent</p>}
                {agents.map((a) => (
                  <div key={a.agentId} className="row">
                    <span className="flex-1">{a.name} <span className="hint">({a.modelProvider}/{a.modelName ?? '-'})</span></span>
                    {btn('删除', () => deleteAgent(a.agentId))}
                  </div>
                ))}
              </div>
              <div className="form-block">
                {input('名称（如 代码评审助手）', agentForm.name, (v) => setAgentForm({ ...agentForm, name: v }))}
                {input('模型供应商编码（如 zhipu）', agentForm.modelProvider, (v) => setAgentForm({ ...agentForm, modelProvider: v }))}
                {input('模型名（如 glm-4-flash）', agentForm.modelName, (v) => setAgentForm({ ...agentForm, modelName: v }))}
                <textarea placeholder="系统提示词" value={agentForm.systemPrompt}
                  onChange={(e) => setAgentForm({ ...agentForm, systemPrompt: e.target.value })}
                  className="w-full bg-[#0a0f0d] text-[#c9d1d9] border border-[#21262d] rounded-md p-2 text-xs mb-2 outline-none" rows={3} />
                {btn('创建 Agent', createAgent, true)}
              </div>
            </div>
          )}

          {section === 'mcp' && (
            <div>
              <div className="list-block">
                {mcps.length === 0 && <p className="hint">暂无 MCP 服务器</p>}
                {mcps.map((m) => (
                  <div key={m.serverId} className="row">
                    <span className="flex-1">{m.name} <span className="hint">[{m.transport}] {m.url || m.command}</span></span>
                    {btn('工具', () => discoverTools(m.serverId))}
                    {btn('删除', () => deleteMcp(m.serverId))}
                  </div>
                ))}
                {Object.entries(mcpTools).map(([id, tools]) => (
                  <div key={id} className="hint">↳ {id}: {tools.map((t) => t.name).join(', ') || '（无工具）'}</div>
                ))}
              </div>
              <div className="form-block">
                {input('名称', mcpForm.name, (v) => setMcpForm({ ...mcpForm, name: v }))}
                <select value={mcpForm.transport} onChange={(e) => setMcpForm({ ...mcpForm, transport: e.target.value })}
                  className="w-full bg-[#0a0f0d] text-[#c9d1d9] border border-[#21262d] rounded-md p-2 text-xs mb-2 outline-none">
                  <option value="stdio">stdio（本地子进程）</option>
                  <option value="streamable_http">streamable_http（远程）</option>
                </select>
                {mcpForm.transport === 'stdio'
                  ? input('启动命令（如 npx -y @modelcontextprotocol/server-filesystem /path）', mcpForm.command, (v) => setMcpForm({ ...mcpForm, command: v }))
                  : input('URL（如 https://host/mcp）', mcpForm.url, (v) => setMcpForm({ ...mcpForm, url: v }))}
                {btn('注册 MCP 服务器', createMcp, true)}
                <p className="hint">注册后模型可经 mcp 桥接使用其工具；删除会断开连接缓存。</p>
              </div>
            </div>
          )}

          {section === 'skills' && (
            <div>
              <div className="list-block">
                {skills.length === 0 && <p className="hint">暂无技能（安装后由 project.context 与系统提示词装载）</p>}
                {skills.map((s) => (
                  <div key={s.name} className="row">
                    <span className="flex-1">{s.name} <span className="hint">{s.preview?.slice(0, 40)}</span></span>
                    {btn('卸载', () => deleteSkill(s.name))}
                  </div>
                ))}
              </div>
              <div className="form-block">
                {input('技能名（如 sm4-skill）', skillForm.name, (v) => setSkillForm({ ...skillForm, name: v }))}
                <textarea placeholder="技能内容（Markdown 指令）" value={skillForm.content}
                  onChange={(e) => setSkillForm({ ...skillForm, content: e.target.value })}
                  className="w-full bg-[#0a0f0d] text-[#c9d1d9] border border-[#21262d] rounded-md p-2 text-xs mb-2 outline-none font-mono" rows={4} />
                {btn('安装技能', installSkill, true)}
                <p className="hint">技能写入工作区 .gewu/skills/，Agent 对话时经 project.context 装载。</p>
              </div>
            </div>
          )}

          {section === 'entry' && (
            <div>
              <div className="form-block">
                <div className="row mb-2">
                  <select value={entryScope} onChange={(e) => setEntryScope(e.target.value as 'global' | 'project')}
                    className="bg-[#0a0f0d] text-[#c9d1d9] border border-[#21262d] rounded-md p-2 text-xs outline-none">
                    <option value="global">全局（~/.gewu）</option>
                    <option value="project">项目级（项目根目录）</option>
                  </select>
                  <select value={entryName} onChange={(e) => setEntryName(e.target.value as 'GEWU.md' | 'AGENTS.md')}
                    className="bg-[#0a0f0d] text-[#c9d1d9] border border-[#21262d] rounded-md p-2 text-xs outline-none">
                    <option value="GEWU.md">GEWU.md</option>
                    <option value="AGENTS.md">AGENTS.md</option>
                  </select>
                </div>
                {entryScope === 'project' && input('项目目录（如 /home/me/projects/demo）', entryProject, setEntryProject)}
                {entryPath && <p className="hint">当前文件：{entryPath}</p>}
                <textarea placeholder={'入口调度指令（Markdown）\n---\nversion: 1.0.0\n---\n\n# 项目规范\n- 构建命令…'} value={entryContent}
                  onChange={(e) => setEntryContent(e.target.value)}
                  className="w-full bg-[#0a0f0d] text-[#c9d1d9] border border-[#21262d] rounded-md p-2 text-xs mb-2 outline-none font-mono" rows={12} />
                {btn('保存', saveEntry, true)}
                <p className="hint">
                  会话启动自动注入 system prompt（AGENTS.md 生态兼容，GEWU.md 格物专属优先）。
                  文件头 version: x.y.z 声明版本——全局与项目两侧都带版本时版本高者生效；
                  默认项目级优先，导入外部 AGENTS.md 无需修改。
                </p>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
