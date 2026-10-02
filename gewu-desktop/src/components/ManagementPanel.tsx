import { useCallback, useEffect, useState } from 'react';
import type { EngineConnection } from '../engine';

/**
 * 管理面板（Phase 2）：四标签 CRUD——模型供应商 / Agent / MCP 服务器 / 技能库。
 * 全部走 EngineConnection（自愈 + 信封解包），端点与引擎 Profile 一一对应。
 */
type Tab = 'providers' | 'agents' | 'mcp' | 'skills';

interface AgentItem { agentId: string; name: string; status: number; modelProvider?: string; modelName?: string; systemPrompt?: string }
interface McpItem { serverId: string; name: string; transport: string; url?: string; command?: string }
interface SkillItem { name: string; preview?: string }

export default function ManagementPanel({ conn, onClose, onChanged }: {
  conn: EngineConnection;
  onClose: () => void;
  onChanged?: () => void;
}) {
  const [tab, setTab] = useState<Tab>('providers');
  const [msg, setMsg] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 数据
  const [agents, setAgents] = useState<AgentItem[]>([]);
  const [mcps, setMcps] = useState<McpItem[]>([]);
  const [skills, setSkills] = useState<SkillItem[]>([]);
  const [mcpTools, setMcpTools] = useState<Record<string, Array<{ name: string; description?: string }>>>({});

  // 表单
  const [agentForm, setAgentForm] = useState({ name: '', modelProvider: '', modelName: '', systemPrompt: '' });
  const [mcpForm, setMcpForm] = useState({ name: '', transport: 'stdio', command: '', url: '' });
  const [skillForm, setSkillForm] = useState({ name: '', content: '' });

  const load = useCallback(async () => {
    try {
      setAgents((await conn.data('/v1/agents')) as AgentItem[]);
    } catch { /* 忽略 */ }
    try {
      setMcps((await conn.data('/v1/mcp-servers')) as McpItem[]);
    } catch { /* 忽略 */ }
    try {
      setSkills((await conn.data('/v1/skills')) as SkillItem[]);
    } catch { /* 忽略 */ }
  }, [conn]);

  useEffect(() => { load(); }, [load]);

  const run = useCallback(async (fn: () => Promise<void>, ok: string) => {
    setBusy(true);
    try { await fn(); setMsg(ok); } catch (e) { setMsg(`失败: ${String(e)}`); } finally { setBusy(false); }
  }, []);

  // ==================== 操作 ====================
  const createAgent = () => run(async () => {
    if (!agentForm.name) throw new Error('name 必填');
    await conn.fetch('/v1/agents', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(agentForm),
    });
    setAgentForm({ name: '', modelProvider: '', modelName: '', systemPrompt: '' });
    await load();
  }, `Agent 已创建`);

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
  }, `MCP 服务器已注册`);

  const deleteMcp = (id: string) => run(async () => {
    await conn.fetch(`/v1/mcp-servers/${id}`, { method: 'DELETE' });
    await load();
  }, `MCP ${id} 已删除`);

  const discoverTools = (id: string) => run(async () => {
    const tools = (await conn.data(`/v1/mcp-servers/${id}/tools`)) as Array<{ name: string; description?: string }>;
    setMcpTools((prev) => ({ ...prev, [id]: tools }));
  }, `发现 ${'工具'}完成`);

  const installSkill = () => run(async () => {
    if (!skillForm.name) throw new Error('name 必填');
    await conn.fetch(`/v1/skills/${encodeURIComponent(skillForm.name)}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: skillForm.content }),
    });
    setSkillForm({ name: '', content: '' });
    await load();
  }, `技能已安装`);

  const deleteSkill = (name: string) => run(async () => {
    await conn.fetch(`/v1/skills/${encodeURIComponent(name)}`, { method: 'DELETE' });
    await load();
  }, `技能 ${name} 已卸载`);

  // ==================== 渲染 ====================
  const input = (placeholder: string, value: string, onChange: (v: string) => void, type = 'text') => (
    <input placeholder={placeholder} value={value} type={type}
      onChange={(e) => onChange(e.target.value)}
      className="w-full bg-[#0a0f0d] text-[#c9d1d9] border border-[#21262d] rounded-md p-2 text-xs mb-2 outline-none focus:border-[#00d4aa]" />
  );
  const btn = (label: string, onClick: () => void, primary = false) => (
    <button className={`btn ${primary ? 'primary' : ''}`} onClick={onClick} disabled={busy}>{label}</button>
  );

  return (
    <div className="overlay" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="dialog" style={{ width: 760 }}>
        <div className="dialog-head">
          <span className="dialog-title">管理面板</span>
          <button className="btn small" onClick={onClose}>关闭</button>
        </div>
        <div className="tab-row">
          {(['providers', 'agents', 'mcp', 'skills'] as Tab[]).map((t) => (
            <button key={t} className={`btn small ${tab === t ? 'chip' : ''}`}
              onClick={() => { setTab(t); setMsg(null); }}>
              {{ providers: '模型供应商', agents: 'Agent', mcp: 'MCP 服务器', skills: '技能库' }[t]}
            </button>
          ))}
        </div>
        {msg && <p className="hint">{msg}</p>}

        {tab === 'providers' && (
          <div>
            <p className="hint">模型供应商在「设置」浮层维护（密钥经 SecretVault 加密）。</p>
          </div>
        )}

        {tab === 'agents' && (
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

        {tab === 'mcp' && (
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

        {tab === 'skills' && (
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
  );
}
