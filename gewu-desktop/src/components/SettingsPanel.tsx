import { useCallback, useEffect, useState } from 'react';
import type { EngineConnection } from '../engine';

/**
 * 统一设置面板（对齐 web 设置页形态）：左侧导航 + 右侧内容区。
 * - 模型设置：与 web 一致的两栏结构——先供应商（编码/显示名/BaseURL/Key），
 *   再供应商下模型清单（模型名/显示名，多模型），密钥经引擎侧 SecretVault 加密；
 * - 子智能体 / MCP 服务器 / 技能库：自 ManagementPanel 平移（端点不变）。
 */
type Section = 'models' | 'agents' | 'mcp' | 'skills';

interface AgentItem { agentId: string; name: string; status: number; modelProvider?: string; modelName?: string; systemPrompt?: string }
interface McpItem { serverId: string; name: string; transport: string; url?: string; command?: string }
interface SkillItem { name: string; preview?: string }
interface ProviderItem {
  code: string; displayName?: string; baseUrl?: string;
  modelsJson?: string; keyConfigured?: boolean;
}
interface ModelRow { name: string; displayName: string }

const SECTION_LABEL: Record<Section, string> = {
  models: '模型设置', agents: '子智能体', mcp: 'MCP 服务器', skills: '技能库',
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

  // ===== 表单 =====
  const [agentForm, setAgentForm] = useState({ name: '', modelProvider: '', modelName: '', systemPrompt: '' });
  const [mcpForm, setMcpForm] = useState({ name: '', transport: 'stdio', command: '', url: '' });
  const [skillForm, setSkillForm] = useState({ name: '', content: '' });

  // ===== 模型设置状态：选中供应商 + 编辑表单 =====
  const [selected, setSelected] = useState<string | null>(null); // code 或 '__new__'
  const [provForm, setProvForm] = useState<{ code: string; displayName: string; baseUrl: string; apiKey: string; keyConfigured: boolean }>(
    { code: '', displayName: '', baseUrl: '', apiKey: '', keyConfigured: false });
  const [modelRows, setModelRows] = useState<ModelRow[]>([]);

  const load = useCallback(async () => {
    try { setAgents((await conn.data('/v1/agents')) as AgentItem[]); } catch { /* 忽略 */ }
    try { setMcps((await conn.data('/v1/mcp-servers')) as McpItem[]); } catch { /* 忽略 */ }
    try { setSkills((await conn.data('/v1/skills')) as SkillItem[]); } catch { /* 忽略 */ }
    try { setProviders((await conn.data('/v1/engine/providers')) as ProviderItem[]); } catch { /* 忽略 */ }
  }, [conn]);

  useEffect(() => { load(); }, [load]);

  const run = useCallback(async (fn: () => Promise<void>, ok: string) => {
    setBusy(true);
    try { await fn(); setMsg(ok); onChanged?.(); } catch (e) { setMsg(`失败: ${String(e)}`); } finally { setBusy(false); }
  }, [onChanged]);

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
    try { models = p?.modelsJson ? (JSON.parse(p.modelsJson) as ModelRow[]) : []; } catch { models = []; }
    setProvForm({
      code: p?.code ?? code, displayName: p?.displayName ?? '', baseUrl: p?.baseUrl ?? '',
      apiKey: '', keyConfigured: Boolean(p?.keyConfigured),
    });
    setModelRows(models);
  }, [providers]);

  const saveProvider = () => run(async () => {
    if (!provForm.code.trim()) throw new Error('编码必填');
    if (modelRows.some((m) => !m.name.trim())) throw new Error('模型清单存在未填名的行');
    await conn.fetch('/v1/engine/providers', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        code: provForm.code.trim(), displayName: provForm.displayName.trim() || provForm.code.trim(),
        baseUrl: provForm.baseUrl.trim(), apiKey: provForm.apiKey.trim() || undefined,
        modelsJson: JSON.stringify(modelRows.filter((m) => m.name.trim())),
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
                        onClick={() => setModelRows((rows) => [...rows, { name: '', displayName: '' }])}>＋ 添加模型</button>
                    </div>
                    {modelRows.length === 0 && <p className="hint">暂无模型——至少添加一个模型供对话选择</p>}
                    {modelRows.map((m, i) => (
                      <div key={i} className="model-row">
                        <input placeholder="模型名（如 glm-4-flash）" value={m.name}
                          onChange={(e) => setModelRows((rows) => rows.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
                        <input placeholder="显示名（可选）" value={m.displayName}
                          onChange={(e) => setModelRows((rows) => rows.map((x, j) => (j === i ? { ...x, displayName: e.target.value } : x)))} />
                        <button className="btn small danger" disabled={busy}
                          onClick={() => setModelRows((rows) => rows.filter((_, j) => j !== i))}>删除</button>
                      </div>
                    ))}
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
        </div>
      </div>
    </div>
  );
}
