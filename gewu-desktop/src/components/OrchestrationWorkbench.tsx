/**
 * 编排工作台（Phase 3 · D2-6 渲染层）：本地多 Agent 编排。
 * 四模式模板（PIPELINE/SUPERVISOR/DEBATE/SWARM）→ 画布（节点状态实时着色 + 产出预览）+
 * 节点编辑 / JSON 直编 → 流式执行与生命周期（暂停协作式 / 恢复 / 取消）。
 * 事件契约与平台编排流同构：graph_start / node_start / content / thinking /
 * node_complete / graph_complete / error（DesktopOrchestrationController#toDTO）。
 * 检查点为内存态（docs/design/42 §7.6）：应用重启后暂停不可恢复，UI 明示。
 *
 * 图定义契约（引擎 OrchestrationGraph）：AGENT 节点 refId 空 → 图变量
 * modelProvider/modelName 兜底；inputs.message 覆盖上游产出，其余键以参考段追加；
 * ${var.input}=运行目标，${var.<节点ID>}=<节点产出>（VariableTemplates.render）。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { EngineConnection } from '../engine';

// ============ 类型（引擎 OrchestrationGraph 契约的渲染层投影） ============
interface GNode {
  nodeId: string;
  type: string;
  refId?: string | null;
  roleCode?: string | null;
  executionMode?: string | null;
  config?: Record<string, unknown> | null;
  inputs?: Record<string, unknown> | null;
  x?: number | null;
  y?: number | null;
}
interface GEdge {
  fromNode: string;
  toNode: string;
  condition?: string | null;
}
interface Graph {
  graphId: string;
  name: string;
  type?: string;
  mode: string;
  variables?: Record<string, unknown> | null;
  nodes: GNode[];
  edges?: GEdge[] | null;
}
type NodeRunState = 'idle' | 'running' | 'done' | 'failed';
type RunState = 'idle' | 'running' | 'paused' | 'done' | 'failed' | 'cancelled';
interface NodeRT {
  state: NodeRunState;
  output: string;
}
interface ConsoleLine {
  id: number;
  ts: string;
  type: string;
  nodeId?: string;
  text: string;
}
interface AgentItem {
  agentId: string;
  name: string;
  status?: number;
  modelProvider?: string;
  modelName?: string;
}
interface ModelItem {
  provider: string;
  modelName: string;
  displayName: string;
}

const NODE_W = 190;
const NODE_H = 118;
const OUTPUT_CAP = 4000; // 节点产出缓冲上限（防长流撑爆内存）

// ============ 模板库（引擎可直接执行的图：AGENT 空绑定 + 图变量模型兜底） ============
function mkNode(id: string, role: string, x: number, y: number, opts?: { type?: string; message?: string }): GNode {
  const n: GNode = { nodeId: id, type: opts?.type ?? 'AGENT', roleCode: role, x, y };
  if (opts?.message) n.inputs = { message: opts.message };
  return n;
}
function mkGraph(mode: string, name: string, nodes: GNode[], edges: GEdge[]): Graph {
  return { graphId: `local-${mode.toLowerCase()}-${Date.now()}`, name, type: 'AD_HOC', mode, nodes, edges };
}
const TEMPLATES: Record<string, () => Graph> = {
  PIPELINE: () =>
    mkGraph('PIPELINE', '流水线：分析 → 实施 → 评审', [
      mkNode('n1', 'ANALYST', 40, 120),
      mkNode('n2', 'BUILDER', 290, 120, { message: '基于上游分析产出，给出具体实施方案与步骤。' }),
      mkNode('n3', 'REVIEWER', 540, 120, { message: '评审上游方案，指出风险与改进项，给出最终结论。' }),
    ], [
      { fromNode: 'n1', toNode: 'n2' },
      { fromNode: 'n2', toNode: 'n3' },
    ]),
  SUPERVISOR: () =>
    mkGraph('SUPERVISOR', '主管调度：统筹 + 双专家', [
      mkNode('s1', 'SUPERVISOR', 40, 120, { message: '你是主管：拆解运行目标并给两位专家分派任务要点。' }),
      mkNode('e1', 'EXPERT', 290, 50),
      mkNode('e2', 'EXPERT', 290, 200),
    ], [
      { fromNode: 's1', toNode: 'e1' },
      { fromNode: 's1', toNode: 'e2' },
    ]),
  DEBATE: () =>
    mkGraph('DEBATE', '辩论：正方 / 反方 + 仲裁', [
      mkNode('d1', 'PRO', 40, 50, { message: '你是正方：围绕运行目标提出并论证你的主张。' }),
      mkNode('d2', 'CON', 40, 200, { message: '你是反方：针对运行目标提出反对与风险论证。' }),
      mkNode('j1', 'JUDGE', 290, 120, { type: 'MERGE' }),
    ], [
      { fromNode: 'd1', toNode: 'j1' },
      { fromNode: 'd2', toNode: 'j1' },
    ]),
  SWARM: () =>
    mkGraph('SWARM', '群体协作（三 Agent）', [
      mkNode('w1', 'WORKER-A', 40, 40),
      mkNode('w2', 'WORKER-B', 290, 120),
      mkNode('w3', 'WORKER-C', 540, 40),
    ], []),
};

const MODE_LABEL: Record<string, string> = {
  PIPELINE: '流水线 PIPELINE',
  SUPERVISOR: '主管调度 SUPERVISOR',
  DEBATE: '辩论仲裁 DEBATE',
  SWARM: '群体协作 SWARM',
};
const RUN_LABEL: Record<RunState, string> = {
  idle: '待运行', running: '运行中', paused: '已暂停', done: '已完成', failed: '失败', cancelled: '已取消',
};
const TYPE_ICON: Record<string, string> = {
  AGENT: '◆', TOOL: '⚒', HUMAN: '✋', ROUTER: '⑂', PARALLEL: '⫸', MERGE: '⨇', SUBGRAPH: '⧉', PLAN: '☰',
};

function autoLayout(nodes: GNode[]): GNode[] {
  // 无坐标的图（JSON 导入）：网格布局（每行 3 个），模板节点保持自带 x/y
  return nodes.map((n, i) => {
    if (typeof n.x === 'number' && typeof n.y === 'number') return n;
    return { ...n, x: 40 + (i % 3) * 250, y: 40 + Math.floor(i / 3) * 170 };
  });
}

export default function OrchestrationWorkbench({ conn, onBackHome }: {
  conn: EngineConnection;
  onBackHome: () => void;
}) {
  const [graph, setGraph] = useState<Graph>(() => TEMPLATES.PIPELINE());
  const [graphText, setGraphText] = useState<string>(() => JSON.stringify(TEMPLATES.PIPELINE(), null, 2));
  const [tab, setTab] = useState<'nodes' | 'json'>('nodes');
  const [selected, setSelected] = useState<string | null>('n1');
  const [goal, setGoal] = useState('');
  const [modelSel, setModelSel] = useState('');
  const [models, setModels] = useState<ModelItem[]>([]);
  const [agents, setAgents] = useState<AgentItem[]>([]);
  const [runState, setRunState] = useState<RunState>('idle');
  const [executionId, setExecutionId] = useState('');
  const [nodeRT, setNodeRT] = useState<Record<string, NodeRT>>({});
  const [consoleLines, setConsoleLines] = useState<ConsoleLine[]>([]);
  const [finalOutput, setFinalOutput] = useState<string | null>(null);
  const [jsonError, setJsonError] = useState<string | null>(null);
  const [inFlight, setInFlight] = useState(false); // SSE 消费中（防止重复启动）
  const consoleRef = useRef<HTMLDivElement>(null);
  const lineSeq = useRef(0);

  useEffect(() => {
    (async () => {
      try { setAgents((await conn.data('/v1/agents')) as AgentItem[]); } catch { /* 忽略 */ }
      try { setModels((await conn.data('/v1/ai/models')) as ModelItem[]); } catch { /* 忽略 */ }
    })();
  }, [conn]);

  useEffect(() => {
    consoleRef.current?.scrollTo({ top: consoleRef.current.scrollHeight });
  }, [consoleLines]);

  // ============ 图编辑 ============
  const applyGraph = useCallback((g: Graph) => {
    setGraph(g);
    setGraphText(JSON.stringify(g, null, 2));
  }, []);
  const loadTemplate = useCallback((mode: string) => {
    const g = (TEMPLATES[mode] ?? TEMPLATES.PIPELINE)();
    applyGraph(g);
    setSelected(g.nodes[0]?.nodeId ?? null);
    setRunState('idle'); setNodeRT({}); setFinalOutput(null); setExecutionId('');
  }, [applyGraph]);
  const patchNode = useCallback((nodeId: string, patch: Partial<GNode>) => {
    const next: Graph = { ...graph, nodes: graph.nodes.map((n) => (n.nodeId === nodeId ? { ...n, ...patch } : n)) };
    setGraph(next);
    setGraphText(JSON.stringify(next, null, 2));
  }, [graph]);
  const addNode = useCallback(() => {
    const laid = autoLayout(graph.nodes);
    const id = `n${graph.nodes.length + 1}-${Date.now() % 1000}`;
    const node = mkNode(id, 'AGENT', 40 + (laid.length % 3) * 250, 40 + Math.floor(laid.length / 3) * 170);
    const next: Graph = { ...graph, nodes: [...laid, node] };
    setGraph(next);
    setGraphText(JSON.stringify(next, null, 2));
    setSelected(id);
  }, [graph]);
  const removeNode = useCallback((nodeId: string) => {
    const next: Graph = {
      ...graph,
      nodes: graph.nodes.filter((n) => n.nodeId !== nodeId),
      edges: (graph.edges ?? []).filter((e) => e.fromNode !== nodeId && e.toNode !== nodeId),
    };
    setGraph(next);
    setGraphText(JSON.stringify(next, null, 2));
    setSelected(null);
  }, [graph]);
  const applyJson = useCallback(() => {
    try {
      const g = JSON.parse(graphText) as Graph;
      if (!g || !Array.isArray(g.nodes)) throw new Error('nodes 必须为数组');
      if (!g.mode) throw new Error('缺少 mode');
      setJsonError(null);
      applyGraph(g);
      setSelected(g.nodes[0]?.nodeId ?? null);
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : String(e));
    }
  }, [graphText, applyGraph]);
  const pickModel = useCallback((sel: string) => {
    setModelSel(sel);
    const variables = { ...(graph.variables ?? {}) };
    if (!sel) {
      delete variables.modelProvider;
      delete variables.modelName;
    } else {
      const m = models.find((x) => `${x.provider}|${x.modelName}` === sel);
      if (m) { variables.modelProvider = m.provider; variables.modelName = m.modelName; }
    }
    const next: Graph = { ...graph, variables };
    setGraph(next);
    setGraphText(JSON.stringify(next, null, 2));
  }, [graph, models]);
  const patchGraphName = useCallback((name: string) => {
    const next: Graph = { ...graph, name };
    setGraph(next);
    setGraphText(JSON.stringify(next, null, 2));
  }, [graph]);

  // ============ 流事件处理 ============
  const pushConsole = useCallback((type: string, nodeId: string | null, text: string) => {
    lineSeq.current += 1;
    const ts = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    setConsoleLines((prev) => [...prev.slice(-400), { id: lineSeq.current, ts, type, nodeId: nodeId ?? undefined, text }]);
  }, []);
  const handleEvent = useCallback((evt: Record<string, unknown>) => {
    const type = String(evt.type ?? '');
    const meta = (evt.metadata ?? {}) as Record<string, unknown>;
    const nodeId = evt.nodeId == null ? null : String(evt.nodeId);
    if (type === 'graph_start') {
      setExecutionId(String(meta.executionId ?? evt.executionId ?? ''));
      pushConsole(type, null, `mode=${String(meta.mode ?? '?')}`);
    } else if (type === 'node_start') {
      if (nodeId) setNodeRT((p) => ({ ...p, [nodeId]: { state: 'running', output: '' } }));
      pushConsole(type, nodeId, '节点开始执行');
    } else if (type === 'content' || type === 'thinking') {
      const chunk = String(evt.content ?? evt.reasoning ?? '');
      if (nodeId && chunk) {
        setNodeRT((p) => {
          const cur = p[nodeId] ?? { state: 'running' as NodeRunState, output: '' };
          const output = (cur.output + chunk).slice(-OUTPUT_CAP);
          return { ...p, [nodeId]: { ...cur, output } };
        });
      }
    } else if (type === 'node_complete') {
      if (nodeId) setNodeRT((p) => ({ ...p, [nodeId]: { state: 'done', output: p[nodeId]?.output ?? '' } }));
      pushConsole(type, nodeId, `outputLen=${String(meta.outputLen ?? '?')}${meta.retries ? ` retries=${String(meta.retries)}` : ''}`);
    } else if (type === 'graph_complete') {
      const status = String(meta.status ?? 'SUCCESS');
      setRunState(status === 'PAUSED' ? 'paused' : status === 'FAILED' ? 'failed' : 'done');
      if (meta.output) setFinalOutput(String(meta.output));
      pushConsole(type, null, `status=${status}`);
    } else if (type === 'error') {
      if (nodeId) setNodeRT((p) => ({ ...p, [nodeId]: { state: 'failed', output: p[nodeId]?.output ?? '' } }));
      setRunState('failed');
      pushConsole('error', nodeId, String(evt.errorMessage ?? '编排执行失败'));
    } else if (type) {
      // tool_call / tool_result / status 等：控制台带摘要
      const text = type.startsWith('tool')
        ? `${String((evt.toolCall as { name?: string })?.name ?? '')} ${String(evt.content ?? '').slice(0, 120)}`
        : String(evt.content ?? '').slice(0, 120);
      pushConsole(type, nodeId, text);
    }
  }, [pushConsole]);

  // ============ 执行与生命周期 ============
  const runGraph = useCallback(async () => {
    if (inFlight || !graph.nodes.length) return;
    setInFlight(true);
    setRunState('running'); setNodeRT({}); setFinalOutput(null); setConsoleLines([]); setExecutionId('');
    try {
      await conn.streamSse('/v1/orchestration/graphs/execute/stream', { graph, input: goal }, handleEvent);
      setRunState((s) => (s === 'running' ? 'done' : s)); // 流收尾兜底（正常应已由 graph_complete 定态）
    } catch (e) {
      setRunState('failed');
      pushConsole('error', null, e instanceof Error ? e.message : String(e));
    } finally {
      setInFlight(false);
    }
  }, [conn, graph, goal, handleEvent, inFlight, pushConsole]);
  const resumeGraph = useCallback(async () => {
    if (inFlight || !executionId) return;
    setInFlight(true);
    setRunState('running');
    pushConsole('status', null, '从暂停检查点续跑…');
    try {
      await conn.streamSse(`/v1/orchestration/executions/${executionId}/resume`, undefined, handleEvent);
      setRunState((s) => (s === 'running' ? 'done' : s));
    } catch (e) {
      setRunState('paused'); // 恢复失败（无检查点/已恢复）回到暂停态
      pushConsole('error', null, e instanceof Error ? e.message : String(e));
    } finally {
      setInFlight(false);
    }
  }, [conn, executionId, handleEvent, inFlight, pushConsole]);
  const pauseGraph = useCallback(async () => {
    if (!executionId) return;
    try {
      await conn.data(`/v1/orchestration/executions/${executionId}/pause`, { method: 'POST' });
      pushConsole('status', null, '暂停信号已发出：当前节点完成后生效');
    } catch (e) {
      pushConsole('error', null, e instanceof Error ? e.message : String(e));
    }
  }, [conn, executionId, pushConsole]);
  const cancelGraph = useCallback(async () => {
    if (!executionId) return;
    try {
      await conn.data(`/v1/orchestration/executions/${executionId}/cancel`, { method: 'POST' });
      setRunState('cancelled');
      pushConsole('status', null, '已取消');
    } catch (e) {
      pushConsole('error', null, e instanceof Error ? e.message : String(e));
    }
  }, [conn, executionId, pushConsole]);

  // ============ 画布几何 ============
  const laidNodes = useMemo(() => autoLayout(graph.nodes), [graph.nodes]);
  const canvasW = Math.max(720, ...laidNodes.map((n) => (n.x ?? 0) + NODE_W + 60));
  const canvasH = Math.max(420, ...laidNodes.map((n) => (n.y ?? 0) + NODE_H + 60));
  const selNode = laidNodes.find((n) => n.nodeId === selected) ?? null;

  const renderNode = (n: GNode) => {
    const rt = nodeRT[n.nodeId] ?? { state: 'idle' as NodeRunState, output: '' };
    return (
      <div
        key={n.nodeId}
        className={`orch-node st-${rt.state} ${selected === n.nodeId ? 'sel' : ''}`}
        style={{ left: n.x ?? 0, top: n.y ?? 0, width: NODE_W, height: NODE_H }}
        onClick={() => setSelected(n.nodeId)}
      >
        <div className="orch-node-head">
          <span className="orch-node-icon">{TYPE_ICON[n.type] ?? '◇'}</span>
          <span className="orch-node-role">{n.roleCode || n.nodeId}</span>
          <span className={`orch-dot d-${rt.state}`} />
        </div>
        <div className="orch-node-id">{n.type}{n.refId ? ` · ${n.refId.slice(0, 8)}` : ''}</div>
        <div className="orch-node-out">{rt.output ? rt.output.slice(-220) : rt.state === 'running' ? '执行中…' : '　'}</div>
      </div>
    );
  };

  return (
    <div className="orch">
      <div className="orch-head">
        <button className="btn small" onClick={onBackHome}>← 主页</button>
        <span className="orch-title">编排工作台</span>
        <span className={`orch-status s-${runState}`}>{RUN_LABEL[runState]}</span>
        {executionId && <span className="chip-static">exec · {executionId.slice(-8)}</span>}
        <span className="orch-hint">检查点为内存态：应用重启后暂停不可恢复</span>
      </div>
      <div className="orch-toolbar">
        <label>模板</label>
        <select
          value={graph.mode}
          onChange={(e) => loadTemplate(e.target.value)}
        >
          {Object.keys(TEMPLATES).map((m) => <option key={m} value={m}>{MODE_LABEL[m]}</option>)}
        </select>
        <input
          className="orch-name"
          value={graph.name}
          onChange={(e) => patchGraphName(e.target.value)}
        />
        <label>模型</label>
        <select value={modelSel} onChange={(e) => pickModel(e.target.value)}>
          <option value="">默认（引擎解析首个可用）</option>
          {models.map((m) => (
            <option key={`${m.provider}|${m.modelName}`} value={`${m.provider}|${m.modelName}`}>
              {m.provider} / {m.displayName || m.modelName}
            </option>
          ))}
        </select>
      </div>
      <div className="orch-goal">
        <textarea
          placeholder="运行目标（必填；播种为 ${var.input} 供起始节点/辩题引用）"
          value={goal}
          onChange={(e) => setGoal(e.target.value)}
        />
        <div className="orch-actions">
          <button className="btn primary" disabled={inFlight || runState === 'running' || runState === 'paused' || !goal.trim()} onClick={runGraph}>▶ 运行</button>
          <button className="btn" disabled={runState !== 'running'} onClick={pauseGraph}>⏸ 暂停</button>
          <button className="btn" disabled={runState !== 'paused' || inFlight} onClick={resumeGraph}>▶ 恢复</button>
          <button className="btn danger" disabled={runState !== 'running' || !executionId} onClick={cancelGraph}>✕ 取消</button>
        </div>
      </div>
      <div className="orch-body">
        <div className="orch-canvas-wrap">
          <div className="orch-canvas" style={{ width: canvasW, height: canvasH }}>
            <svg className="orch-edges" width={canvasW} height={canvasH}>
              <defs>
                <marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                  <path d="M0,0 L8,4 L0,8 Z" fill="rgba(0,184,148,0.55)" />
                </marker>
              </defs>
              {(graph.edges ?? []).map((e, i) => {
                const a = laidNodes.find((n) => n.nodeId === e.fromNode);
                const b = laidNodes.find((n) => n.nodeId === e.toNode);
                if (!a || !b) return null;
                const x1 = (a.x ?? 0) + NODE_W;
                const y1 = (a.y ?? 0) + 30;
                const x2 = b.x ?? 0;
                const y2 = (b.y ?? 0) + 30;
                const st = nodeRT[e.toNode]?.state ?? 'idle';
                const active = st === 'running';
                return (
                  <line
                    key={i}
                    x1={x1} y1={y1} x2={x2 - 4} y2={y2}
                    className={`orch-edge ${active ? 'active' : `t-${st}`}`}
                    markerEnd="url(#arrow)"
                  />
                );
              })}
            </svg>
            {laidNodes.map(renderNode)}
          </div>
        </div>
        <div className="orch-side">
          <div className="tab-row">
            {(['nodes', 'json'] as const).map((t) => (
              <button key={t} className={`btn small ${tab === t ? 'chip' : ''}`} onClick={() => setTab(t)}>
                {{ nodes: '节点编辑', json: 'JSON 直编' }[t]}
              </button>
            ))}
            <button className="btn small" onClick={addNode} disabled={tab !== 'nodes'}>＋ 节点</button>
          </div>
          {tab === 'nodes' && (selNode ? (
            <div className="orch-form">
              <p className="hint">节点 {selNode.nodeId}（{selNode.type}）</p>
              <label>角色显示</label>
              <input
                value={selNode.roleCode ?? ''}
                onChange={(e) => patchNode(selNode.nodeId, { roleCode: e.target.value })}
              />
              <label>指令模板（inputs.message；留空继承上游产出）</label>
              <textarea
                className="orch-msg"
                value={(selNode.inputs?.message as string) ?? ''}
                placeholder={'可用：${var.input} 运行目标；${var.<节点ID>} 上游产出'}
                onChange={(e) => {
                  const v = e.target.value;
                  // 清空 = 移除 message 键（空串会以空输入覆盖上游产出，违背链式语义）
                  const inputs = { ...(selNode.inputs ?? {}) };
                  if (v.trim()) inputs.message = v; else delete inputs.message;
                  patchNode(selNode.nodeId, { inputs: Object.keys(inputs).length ? inputs : null });
                }}
              />
              <label>Agent 绑定（refId；留空跟随图模型）</label>
              <select
                value={selNode.refId ?? ''}
                onChange={(e) => patchNode(selNode.nodeId, { refId: e.target.value || null })}
              >
                <option value="">跟随图模型（variables.modelProvider/modelName）</option>
                {agents.filter((a) => a.status !== 0).map((a) => (
                  <option key={a.agentId} value={a.agentId}>{a.name}{a.modelName ? ` · ${a.modelName}` : ''}</option>
                ))}
              </select>
              <button className="btn small danger" onClick={() => removeNode(selNode.nodeId)}>删除该节点</button>
            </div>
          ) : (
            <p className="hint">在画布点击节点进行编辑，或「＋ 节点」追加</p>
          ))}
          {tab === 'json' && (
            <div className="orch-form">
              <textarea
                className="orch-json"
                value={graphText}
                onChange={(e) => setGraphText(e.target.value)}
                spellCheck={false}
              />
              <div className="orch-json-actions">
                <button className="btn primary small" onClick={applyJson}>应用</button>
                <button className="btn small" onClick={() => { setGraphText(JSON.stringify(graph, null, 2)); setJsonError(null); }}>还原</button>
              </div>
              {jsonError && <p className="error">{jsonError}</p>}
            </div>
          )}
        </div>
      </div>
      {finalOutput && (
        <div className="orch-final">
          <div className="orch-final-label">最终产出</div>
          <pre className="orch-final-pre">{finalOutput}</pre>
        </div>
      )}
      <div className="orch-console" ref={consoleRef}>
        {consoleLines.length === 0 && <p className="hint">运行控制台：编排事件将在此逐条显示（节点产出在画布节点内实时预览）</p>}
        {consoleLines.map((l) => (
          <div key={l.id} className={`console-line c-${l.type}`}>
            <span className="console-ts">{l.ts}</span>
            <span className="console-type">{l.type}</span>
            {l.nodeId && <span className="console-node">{l.nodeId}</span>}
            <span className="console-text">{l.text}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
