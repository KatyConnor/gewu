'use client';
// 编排设计器 - 右侧属性面板：按选中对象（节点/边/画布设置）渲染表单。
// 字段规范见 docs/design/46 报告 §7.4：数据源下拉化，HUMAN.refId 置灰（引擎暂不消费）。
import { useEffect, useState } from 'react';
import { Trash2, Info, Clock, Webhook, Copy } from 'lucide-react';
import CustomSelect from '@/components/ui/Select';
import { useToast } from '@/components/ui/Toast';
import {
  NODE_CATALOG, type DesignerFlowNode,
  type GraphNodeDef, type GraphNodeDef as NodeDef,
} from '@/lib/orchestrationDesigner';
import type { RoleOption, ToolOption } from '@/lib/orchestration';
import {
  upsertSchedule, getSchedule, upsertWebhook, getWebhook,
  type OrchestrationScheduleConfig,
} from '@/lib/orchestration';
import type { AgentDTO } from '@/lib/agent';
import type { ModeOption } from '@/lib/orchestrationDesigner';

export interface Catalogs {
  roles: RoleOption[];
  tools: ToolOption[];
  agents: AgentDTO[];
  /** 已激活编排图（SUBGRAPH 节点 refId 下拉数据源，WFO-04；排除当前图） */
  activeGraphs: { id: string; name: string }[];
}

export interface GraphSettings {
  variablesText: string;
  rootGoalId: string;
  /** 失败传播语义（docs/design/47 问题一）：default=引擎默认 fail-fast；true=best-effort 失败继续 */
  continueOnFailure: 'default' | 'true' | 'false';
}

/** 选中边视图：仅暴露面板需要的字段（与 React Flow 边类型结构兼容） */
export interface SelectedEdgeView {
  id: string;
  source: string;
  target: string;
  data?: { condition?: string };
}

interface Props {
  selectedNode: DesignerFlowNode | null;
  selectedEdge: SelectedEdgeView | null;
  settings: GraphSettings;
  mode: ModeOption;
  catalogs: Catalogs;
  /** 当前图 ID（触发配置区数据源，WFC-02/03） */
  graphId: string;
  onUpdateNodeDef: (nodeId: string, updater: (def: GraphNodeDef) => GraphNodeDef) => void;
  onUpdateEdgeCondition: (edgeId: string, condition: string) => void;
  onDeleteEdge: (edgeId: string) => void;
  onSettingsChange: (patch: Partial<GraphSettings>) => void;
}

const inputClass = 'w-full px-2.5 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-xs text-ink-100 placeholder-ink-500 outline-none focus:border-tech-500/30';

/** JSON 文本域：合法即回写，非法显示错误说明 */
function JsonField({ label, hint, value, onApply }: {
  label: string;
  hint?: string;
  value: unknown;
  onApply: (parsed: Record<string, unknown> | undefined) => void;
}) {
  const initial = value == null ? '' : JSON.stringify(value, null, 2);
  const [text, setText] = useState(initial);
  const [error, setError] = useState('');
  const handleChange = (next: string) => {
    setText(next);
    if (!next.trim()) {
      setError('');
      onApply(undefined);
      return;
    }
    try {
      const parsed: unknown = JSON.parse(next);
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        setError('必须是 JSON 对象');
        return;
      }
      setError('');
      onApply(parsed as Record<string, unknown>);
    } catch {
      setError('不是合法 JSON，未应用修改');
    }
  };
  return (
    <div>
      <label className="mb-1 block text-xs text-ink-400">{label}</label>
      {hint && <p className="mb-1 text-[10px] text-ink-500">{hint}</p>}
      <textarea rows={4} value={text} onChange={e => handleChange(e.target.value)}
        className={`${inputClass} resize-none font-mono`} />
      {error && <p className="mt-1 text-[10px] text-cinnabar-400">{error}</p>}
    </div>
  );
}

/**
 * 触发配置区（WFC-02/WFC-03，画布级）：定时触发（Cron + 下次触发预览 + 启停）
 * 与 Webhook 触发（token 一次性展示/复制/重置/停用）。挂在画布级设置下方。
 */
function TriggerSection({ graphId }: { graphId: string }) {
  const toast = useToast();
  const [cronExpr, setCronExpr] = useState('');
  const [inputTemplate, setInputTemplate] = useState('');
  const [schedule, setSchedule] = useState<OrchestrationScheduleConfig | null>(null);
  const [webhookEnabled, setWebhookEnabled] = useState<boolean | null>(null);
  const [webhookExists, setWebhookExists] = useState(false);
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    getSchedule(graphId)
      .then(s => {
        if (s) {
          setSchedule(s);
          setCronExpr(s.cronExpr || '');
          setInputTemplate(s.inputTemplate || '');
        }
      })
      .catch(() => { /* 未配置或接口不可用 */ });
    getWebhook(graphId)
      .then(w => {
        if (w) {
          setWebhookExists(true);
          setWebhookEnabled(w.enabled === 1);
        }
      })
      .catch(() => { /* 未配置 */ });
  }, [graphId]);

  const saveSchedule = async (enabled: boolean) => {
    if (!cronExpr.trim()) {
      toast('请填写 Cron 表达式', 'error');
      return;
    }
    setBusy(true);
    try {
      const saved = await upsertSchedule(graphId, {
        cronExpr: cronExpr.trim(), inputTemplate, enabled,
      });
      setSchedule(saved);
      toast(enabled ? '定时触发已启用' : '定时触发已停用', 'success');
    } catch (e) {
      toast(e instanceof Error ? e.message : '保存失败', 'error');
    } finally { setBusy(false); }
  };

  const generateToken = async (regenerate: boolean) => {
    setBusy(true);
    try {
      const credential = await upsertWebhook(graphId, { enabled: true, regenerate });
      setToken(credential.token || '');
      setWebhookExists(true);
      setWebhookEnabled(true);
      toast(regenerate ? '已重置 Webhook token（旧 token 立即失效）' : 'Webhook 已生成', 'success');
    } catch (e) {
      toast(e instanceof Error ? e.message : '生成失败', 'error');
    } finally { setBusy(false); }
  };

  const toggleWebhook = async () => {
    setBusy(true);
    try {
      await upsertWebhook(graphId, { enabled: !(webhookEnabled === true) });
      setWebhookEnabled(!(webhookEnabled === true));
      toast(webhookEnabled === true ? 'Webhook 已停用' : 'Webhook 已启用', 'success');
    } catch (e) {
      toast(e instanceof Error ? e.message : '操作失败', 'error');
    } finally { setBusy(false); }
  };

  const webhookUrl = token
    ? `${typeof window !== 'undefined' ? window.location.origin : ''}/api/v1/orchestration/webhooks/${token}`
    : '';

  return (
    <div className="mt-3 space-y-3 border-t border-tech-500/10 pt-3">
      <p className="text-xs font-semibold text-ink-200">触发配置</p>

      {/* 定时触发 */}
      <details className="rounded-lg bg-ink-800/40 px-2.5 py-2">
        <summary className="flex cursor-pointer items-center gap-1.5 text-xs text-ink-300">
          <Clock className="h-3 w-3" />定时触发
          {schedule?.enabled === 1 && <span className="text-[10px] text-tech-400">运行中</span>}
        </summary>
        <div className="mt-2 space-y-2.5">
          <div>
            <label className="mb-1 block text-xs text-ink-400">Cron 表达式（6 位）</label>
            <input type="text" value={cronExpr} className={`${inputClass} font-mono`}
              placeholder="如 0 0 9 * * *（每天 9 点）"
              onChange={e => setCronExpr(e.target.value)} />
          </div>
          <div>
            <label className="mb-1 block text-xs text-ink-400">执行输入模板（可选）</label>
            <textarea rows={2} value={inputTemplate} className={`${inputClass} resize-none`}
              placeholder="原样作为每次触发的执行输入"
              onChange={e => setInputTemplate(e.target.value)} />
          </div>
          {schedule?.nextFireAt && (
            <p className="text-[10px] text-ink-500">下次触发：{new Date(schedule.nextFireAt).toLocaleString()}</p>
          )}
          <div className="flex gap-2">
            <button onClick={() => saveSchedule(true)} disabled={busy}
              className="flex-1 px-2.5 py-1.5 text-xs btn-primary text-white rounded-lg disabled:opacity-50">
              {busy ? '保存中...' : schedule?.enabled === 1 ? '更新并保持启用' : '启用定时'}
            </button>
            {schedule?.enabled === 1 && (
              <button onClick={() => saveSchedule(false)} disabled={busy}
                className="px-2.5 py-1.5 text-xs text-gold-400 border border-gold-500/20 rounded-lg hover:bg-gold-500/10 disabled:opacity-50">
                停用
              </button>
            )}
          </div>
        </div>
      </details>

      {/* Webhook 触发 */}
      <details className="rounded-lg bg-ink-800/40 px-2.5 py-2">
        <summary className="flex cursor-pointer items-center gap-1.5 text-xs text-ink-300">
          <Webhook className="h-3 w-3" />Webhook 触发
          {webhookEnabled === true && <span className="text-[10px] text-tech-400">启用中</span>}
          {webhookEnabled === false && <span className="text-[10px] text-ink-500">已停用</span>}
        </summary>
        <div className="mt-2 space-y-2.5">
          {token && (
            <div>
              <p className="mb-1 text-[10px] text-gold-400">token 仅本次展示，请立即复制（库内只存哈希，丢失须重置）：</p>
              <div className="flex items-center gap-1.5">
                <code className="flex-1 break-all rounded bg-ink-900/60 px-2 py-1.5 text-[10px] font-mono text-ink-200">{webhookUrl}</code>
                <button onClick={() => { navigator.clipboard?.writeText(webhookUrl); toast('已复制到剪贴板', 'success'); }}
                  className="shrink-0 p-1.5 text-ink-400 hover:text-tech-400 rounded" title="复制 URL">
                  <Copy className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
          )}
          <div className="flex gap-2">
            <button onClick={() => generateToken(webhookExists)} disabled={busy}
              className="flex-1 px-2.5 py-1.5 text-xs text-tech-400 border border-tech-500/20 rounded-lg hover:bg-tech-500/10 disabled:opacity-50">
              {webhookExists ? '重置 token（旧 token 失效）' : '生成 Webhook'}
            </button>
            {webhookExists && (
              <button onClick={toggleWebhook} disabled={busy}
                className="px-2.5 py-1.5 text-xs text-gold-400 border border-gold-500/20 rounded-lg hover:bg-gold-500/10 disabled:opacity-50">
                {webhookEnabled === true ? '停用' : '启用'}
              </button>
            )}
          </div>
          <p className="text-[10px] leading-relaxed text-ink-500">
            外部 POST 该 URL 即触发执行（body 原样作为输入）；错误 token 返回 404。服务端总开关 agent.engine.webhook.enabled 默认关闭。
          </p>
        </div>
      </details>
    </div>
  );
}

function NoSelection({ mode }: { mode: ModeOption }) {
  return (
    <div className="space-y-3">
      <p className="text-xs text-ink-500">选中节点或连线后在此配置属性。</p>
      <div className="rounded-lg bg-ink-800/60 p-3 text-[11px] leading-relaxed text-ink-400">
        <p className="mb-1 flex items-center gap-1 font-medium text-ink-200">
          <Info className="h-3 w-3" />当前模式：{mode.label}
        </p>
        <p>{mode.hint}</p>
      </div>
    </div>
  );
}

/** 节点级重试与超时字段（WFO-05，AGENT/TOOL 共用；默认不改变引擎现行为） */
function RetryTimeoutFields({ def, patchConfig }: {
  def: NodeDef;
  patchConfig: (patch: Record<string, unknown>) => void;
}) {
  return (
    <details className="rounded-lg bg-ink-800/40 px-2.5 py-2">
      <summary className="cursor-pointer text-xs text-ink-300">重试与超时（可选）</summary>
      <div className="mt-2 space-y-2.5">
        <div>
          <label className="mb-1 block text-xs text-ink-400">重试次数 retryCount（0-3）</label>
          <input type="number" min={0} max={3} value={String(def.config?.retryCount ?? '')}
            className={inputClass} placeholder="默认 0（不重试）"
            onChange={e => patchConfig({ retryCount: e.target.value === '' ? undefined : Number(e.target.value) })} />
        </div>
        <div>
          <label className="mb-1 block text-xs text-ink-400">重试退避 retryBackoffMs</label>
          <input type="number" min={0} value={String(def.config?.retryBackoffMs ?? '')}
            className={inputClass} placeholder="默认 1000 毫秒"
            onChange={e => patchConfig({ retryBackoffMs: e.target.value === '' ? undefined : Number(e.target.value) })} />
        </div>
        <div>
          <label className="mb-1 block text-xs text-ink-400">超时 timeoutSeconds</label>
          <input type="number" min={0} max={86400} value={String(def.config?.timeoutSeconds ?? '')}
            className={inputClass} placeholder="0 = 不启用节点级超时"
            onChange={e => patchConfig({ timeoutSeconds: e.target.value === '' ? undefined : Number(e.target.value) })} />
          <p className="mt-1 text-[10px] leading-relaxed text-ink-500">
            仅对执行异常/超时重试；超时按失败处理并受失败传播语义约束。
          </p>
        </div>
      </div>
    </details>
  );
}

function EdgeForm({ edge, onUpdateEdgeCondition, onDeleteEdge }: {
  edge: SelectedEdgeView;
  onUpdateEdgeCondition: (edgeId: string, condition: string) => void;
  onDeleteEdge: (edgeId: string) => void;
}) {
  const condition = edge.data?.condition ?? '';
  return (
    <div className="space-y-3">
      <p className="text-xs font-semibold text-ink-200">连线属性</p>
      <p className="font-mono text-[10px] text-ink-500">{edge.source} → {edge.target}</p>
      <div>
        <label className="mb-1 block text-xs text-ink-400">路由条件 condition</label>
        <p className="mb-1 text-[10px] leading-relaxed text-ink-500">
          仅对 ROUTER 节点的出边生效。语法：var:x == &apos;值&apos; / != / contains / 裸变量名；留空为默认边（建议保留一条 else 兜底）。
        </p>
        <input type="text" value={condition} className={`${inputClass} font-mono`}
          placeholder="如 var:decision == 'APPROVED' 或 else"
          onChange={e => onUpdateEdgeCondition(edge.id, e.target.value)} />
      </div>
      <button onClick={() => onDeleteEdge(edge.id)}
        className="flex items-center gap-1.5 px-3 py-1.5 text-xs text-cinnabar-400 border border-cinnabar-500/20 rounded-lg hover:bg-cinnabar-500/10 transition-colors">
        <Trash2 className="h-3.5 w-3.5" />删除连线
      </button>
    </div>
  );
}

function NodeForm({ def, catalogs, onUpdate }: { def: NodeDef; catalogs: Catalogs; onUpdate: (patch: Partial<NodeDef>) => void }) {
  const nodeType = def.type ?? 'AGENT';
  const catalog = NODE_CATALOG[nodeType];
  const patchConfig = (patch: Record<string, unknown>) =>
    onUpdate({ config: { ...(def.config ?? {}), ...patch } });

  return (
    <div className="space-y-3">
      <div>
        <p className="text-xs font-semibold text-ink-200">{catalog.label}</p>
        <p className="font-mono text-[10px] text-ink-500">{def.nodeId}</p>
      </div>
      {nodeType === 'AGENT' && (
        <>
          <div>
            <label className="mb-1 block text-xs text-ink-400">Agent（refId）</label>
            <CustomSelect value={def.refId ?? ''} onChange={v => onUpdate({ refId: v || undefined })}
              options={[{ value: '', label: '不指定（用图变量模型兜底）' },
                ...catalogs.agents.map(a => ({ value: a.agentId, label: `${a.agentName}（${a.modelProvider || '-'}/${a.modelName || '-'}）` }))]} />
          </div>
          <div>
            <label className="mb-1 block text-xs text-ink-400">角色（roleCode）</label>
            <CustomSelect value={def.roleCode ?? ''} onChange={v => onUpdate({ roleCode: v || undefined })}
              options={[{ value: '', label: '未指定' },
                ...catalogs.roles.map(r => ({ value: r.roleCode, label: `${r.roleName}（${r.roleCode}）` }))]} />
          </div>
          <JsonField label="输入映射 inputs" hint="支持 ${名称} 引用（如 ${input}、${节点ID}）；键 message 会覆盖前驱节点产出"
            value={def.inputs} onApply={parsed => onUpdate({ inputs: parsed })} />
          <JsonField label="输出契约 outputSchema" hint="JSON Schema 对象或逗号分隔字段串"
            value={def.config?.outputSchema}
            onApply={parsed => patchConfig({ outputSchema: parsed })} />
          <RetryTimeoutFields def={def} patchConfig={patchConfig} />
        </>
      )}
      {nodeType === 'TOOL' && (
        <>
          <div>
            <label className="mb-1 block text-xs text-ink-400">工具（config.toolName）<span className="text-cinnabar-400">*</span></label>
            <CustomSelect value={String(def.config?.toolName ?? '')} onChange={v => patchConfig({ toolName: v })}
              options={[{ value: '', label: '请选择工具' },
                ...catalogs.tools.map(t => ({ value: t.name, label: `${t.source === 'CODE' ? '[代码]' : '[配置]'} ${t.name}` }))]} />
          </div>
          <JsonField label="工具参数 arguments" hint="支持 ${名称} 占位（如 ${input}、${节点ID}）" value={def.config?.arguments}
            onApply={parsed => patchConfig({ arguments: parsed })} />
          <div>
            <label className="mb-1 block text-xs text-ink-400">产出变量名（outputVar）</label>
            <input type="text" value={String(def.config?.outputVar ?? '')} className={inputClass}
              placeholder="缺省写入以 nodeId 命名的变量"
              onChange={e => patchConfig({ outputVar: e.target.value || undefined })} />
          </div>
          <RetryTimeoutFields def={def} patchConfig={patchConfig} />
        </>
      )}
      {nodeType === 'HUMAN' && (
        <>
          <div>
            <label className="mb-1 block text-xs text-ink-400">审批配置（refId）</label>
            <input type="text" value={def.refId ?? ''} disabled className={`${inputClass} opacity-50`}
              placeholder="暂不可配置" />
            <p className="mt-1 text-[10px] text-ink-500">审批配置实体尚未开放，当前仅记录不参与执行。</p>
          </div>
          <div>
            <label className="mb-1 block text-xs text-ink-400">审批超时（timeoutSeconds）</label>
            <input type="number" min={1} max={86400} value={String(def.config?.timeoutSeconds ?? '')}
              className={inputClass} placeholder="默认 1800"
              onChange={e => patchConfig({ timeoutSeconds: e.target.value ? Number(e.target.value) : undefined })} />
          </div>
          <div>
            <label className="mb-1 block text-xs text-ink-400">指定审批人（assigneeId）</label>
            <input type="text" value={String(def.config?.assigneeId ?? '')} className={inputClass}
              placeholder="用户 ID，留空=全员可见待办"
              onChange={e => patchConfig({ assigneeId: e.target.value || undefined })} />
          </div>
          <div>
            <label className="mb-1 block text-xs text-ink-400">指定审批角色（assigneeRole）</label>
            <input type="text" value={String(def.config?.assigneeRole ?? '')} className={inputClass}
              placeholder="角色编码（与审批人并用）"
              onChange={e => patchConfig({ assigneeRole: e.target.value || undefined })} />
          </div>
        </>
      )}
      {nodeType === 'ROUTER' && (
        <p className="rounded-lg bg-ink-800/60 p-2.5 text-[11px] leading-relaxed text-ink-400">
          点击本节点引出的连线，在右侧编辑路由条件 condition（语法 var:x == &apos;值&apos; / != / contains / else）。
        </p>
      )}
      {nodeType === 'MERGE' && (
        <div>
          <label className="mb-1 block text-xs text-ink-400">合并策略（strategy）</label>
          <CustomSelect value={String(def.config?.strategy ?? '')} onChange={v => patchConfig({ strategy: v || undefined })}
            options={[{ value: '', label: '默认（文本拼接）' }, { value: 'json_merge', label: 'json_merge（JSON 合并）' }]} />
        </div>
      )}
      {nodeType === 'PLAN' && (
        <div>
          <label className="mb-1 block text-xs text-ink-400">目标类型（goalType）</label>
          <input type="text" value={String(def.config?.goalType ?? '')} className={inputClass}
            placeholder="默认 FEATURE"
            onChange={e => patchConfig({ goalType: e.target.value || undefined })} />
        </div>
      )}
      {nodeType === 'SUBGRAPH' && (
        <div>
          <label className="mb-1 block text-xs text-ink-400">子图（refId）<span className="text-cinnabar-400">*</span></label>
          <CustomSelect value={def.refId ?? ''} onChange={v => onUpdate({ refId: v || undefined })}
            options={[{ value: '', label: '请选择已激活的编排图' },
              ...catalogs.activeGraphs.map(g => ({ value: g.id, label: `${g.name}（${g.id.slice(0, 8)}…）` }))]} />
          <p className="mt-1 text-[10px] leading-relaxed text-ink-500">
            以父图变量快照为初始变量沙箱执行（子图内部变量不回渗），子图最终产出作为本节点输出；嵌套深度上限 2。
          </p>
        </div>
      )}
    </div>
  );
}

export default function DesignerPropertyPanel(props: Props) {
  const { selectedNode, selectedEdge, settings, mode, onSettingsChange } = props;
  return (
    <aside className="w-72 shrink-0 overflow-y-auto scrollbar-thin border-l border-tech-500/10 bg-ink-900/40 p-3">
      {selectedNode ? (
        <NodeForm key={selectedNode.id} def={selectedNode.data.def} catalogs={props.catalogs}
          onUpdate={patch => props.onUpdateNodeDef(selectedNode.id, def => ({ ...def, ...patch }))} />
      ) : selectedEdge ? (
        <EdgeForm edge={selectedEdge}
          onUpdateEdgeCondition={props.onUpdateEdgeCondition} onDeleteEdge={props.onDeleteEdge} />
      ) : (
        <NoSelection mode={mode} />
      )}
      {!selectedNode && !selectedEdge && (
        <div className="mt-4 space-y-3 border-t border-tech-500/10 pt-3">
          <p className="text-xs font-semibold text-ink-200">画布级设置</p>          <div>
            <label className="mb-1 block text-xs text-ink-400">失败传播（continueOnFailure）</label>
            <CustomSelect value={settings.continueOnFailure}
              onChange={v => onSettingsChange({ continueOnFailure: v as GraphSettings['continueOnFailure'] })}
              options={[
                { value: 'default', label: '引擎默认（失败即整图终止）' },
                { value: 'true', label: 'best-effort（失败继续其余分支）' },
                { value: 'false', label: '失败即整图终止（显式）' },
              ]} />
            <p className="mt-1 text-[10px] leading-relaxed text-ink-500">
              写入图变量 continueOnFailure：true 时 AGENT 失败后其余分支继续执行，整图终态仍如实标记 FAILED。
            </p>
          </div>
          <JsonField label="图变量 variables" hint="input 为运行时用户输入；modelProvider/modelName 为 Agent 兜底模型"
            value={safeParse(settings.variablesText)}
            onApply={parsed => onSettingsChange({ variablesText: parsed ? JSON.stringify(parsed, null, 2) : '' })} />
          <div>
            <label className="mb-1 block text-xs text-ink-400">关联自主目标（rootGoalId）</label>
            <input type="text" value={settings.rootGoalId} className={inputClass}
              onChange={e => onSettingsChange({ rootGoalId: e.target.value })} />
          </div>
          <TriggerSection graphId={props.graphId} />
        </div>
      )}
    </aside>
  );
}

function safeParse(text: string): Record<string, unknown> | undefined {
  try {
    const parsed: unknown = JSON.parse(text);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>) : undefined;
  } catch {
    // 非法 JSON 视为未配置，画布级校验与保存闸会给出可读错误
    return undefined;
  }
}
