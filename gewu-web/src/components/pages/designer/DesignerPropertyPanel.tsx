'use client';
// 编排设计器 - 右侧属性面板：按选中对象（节点/边/画布设置）渲染表单。
// 字段规范见 docs/design/46 报告 §7.4：数据源下拉化，HUMAN.refId 置灰（引擎暂不消费）。
import { useState } from 'react';
import { Trash2, Info } from 'lucide-react';
import CustomSelect from '@/components/ui/Select';
import {
  EXECUTION_MODE_OPTIONS, NODE_CATALOG, type DesignerFlowNode,
  type GraphNodeDef, type GraphNodeDef as NodeDef,
} from '@/lib/orchestrationDesigner';
import type { RoleOption, ToolOption } from '@/lib/orchestration';
import type { AgentDTO } from '@/lib/agent';
import type { ModeOption } from '@/lib/orchestrationDesigner';

export interface Catalogs {
  roles: RoleOption[];
  tools: ToolOption[];
  agents: AgentDTO[];
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
          <div>
            <label className="mb-1 block text-xs text-ink-400">执行模式</label>
            <CustomSelect value={def.executionMode ?? 'REACT'} onChange={v => onUpdate({ executionMode: v })}
              options={EXECUTION_MODE_OPTIONS} />
            <p className="mt-1 text-[10px] text-gold-400">当前引擎版本该字段未参与节点级执行，仅作声明。</p>
          </div>
          <JsonField label="输入映射 inputs" hint="支持 ${var.xxx} 引用；键 message 会覆盖前驱节点产出"
            value={def.inputs} onApply={parsed => onUpdate({ inputs: parsed })} />
          <JsonField label="输出契约 outputSchema" hint="JSON Schema 对象或逗号分隔字段串"
            value={def.config?.outputSchema}
            onApply={parsed => patchConfig({ outputSchema: parsed })} />
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
          <JsonField label="工具参数 arguments" hint="支持 ${var.xxx} 占位" value={def.config?.arguments}
            onApply={parsed => patchConfig({ arguments: parsed })} />
          <div>
            <label className="mb-1 block text-xs text-ink-400">产出变量名（outputVar）</label>
            <input type="text" value={String(def.config?.outputVar ?? '')} className={inputClass}
              placeholder="缺省写入以 nodeId 命名的变量"
              onChange={e => patchConfig({ outputVar: e.target.value || undefined })} />
          </div>
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
        <p className="rounded-lg bg-gold-500/10 p-2.5 text-[11px] text-gold-400">
          引擎尚未实现子图嵌套，当前按 Agent 节点处理。
        </p>
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
          <p className="text-xs font-semibold text-ink-200">画布级设置</p>
          <div>
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
