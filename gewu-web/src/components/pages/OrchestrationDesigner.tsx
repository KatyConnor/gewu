'use client';
// 编排图设计器主组件（docs/design/46 报告 M1）：
// 左节点库 / 中画布（React Flow）/ 右属性面板 + JSON 视图双向同步 + 实时校验。
// 数据流：画布状态（nodes/edges）与图级设置为唯一事实源，保存时经 flowToDefinition 归一；
// JSON 视图"应用到画布"后反向重建画布状态。
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
// React Flow 交互层样式：节点/端口的 pointer-events、定位与拖拽规则全在此表，
// 缺失会导致节点可渲染但不可拖动/点击、端口错位（截图已验证）
import '@xyflow/react/dist/style.css';
import {
  ReactFlow, ReactFlowProvider, Background, BackgroundVariant, Controls, MiniMap,
  useNodesState, useEdgesState, useReactFlow,
  type Connection, type Node, type Edge,
} from '@xyflow/react';
import { ArrowLeft, Save, Braces, ShieldCheck, Loader2, TriangleAlert, Play, LayoutGrid, History } from 'lucide-react';
import DesignerNodeCard from './designer/DesignerNodeCard';
import DesignerPalette, { NODE_DND_MIME } from './designer/DesignerPalette';
import DesignerPropertyPanel, { type Catalogs, type GraphSettings } from './designer/DesignerPropertyPanel';
import DesignerJsonPanel from './designer/DesignerJsonPanel';
import DesignerRunConsole from './designer/DesignerRunConsole';
import DesignerReplayPanel from './designer/DesignerReplayPanel';
import CustomSelect from '@/components/ui/Select';
import { useToast } from '@/components/ui/Toast';
import {
  updateGraph, listRoleCatalog, listToolCatalog, executeGraphStream, cancelExecution,
  listExecutions, listNodeExecutions, listGraphs,
  type OrchestrationGraphEntity, type OrchestrationExecutionEntity, type OrchestrationNodeExecution,
} from '@/lib/orchestration';
import { listAgents, type AgentDTO } from '@/lib/agent';
import {
  MODE_OPTIONS, GRAPH_TYPE_OPTIONS, NODE_CATALOG, FALLBACK_ROLES,
  definitionToFlow, flowToDefinition, parseDefinition, serializeDefinition,
  validateDefinition, supervisorNodeId, nextEdgeId, layeredLayout,
  type DesignerFlowNode, type DesignerFlowEdge, type DesignerNodeData,
  type GraphNodeDef, type GraphDefinition,
  type OrchNodeType, type ValidationIssue,
  type OrchestrationRunEvent, type NodeRunState,
} from '@/lib/orchestrationDesigner';

type OrchestrationNode = Node<DesignerNodeData, 'orchestration'>;
type OrchestrationEdge = Edge<{ condition?: string }>;

const nodeTypes = { orchestration: DesignerNodeCard };
const SKELETON_DEFINITION = '{"nodes":[],"edges":[]}';

function uniqueNodeId(existing: Set<string>): string {
  let index = existing.size + 1;
  while (existing.has(`n${index}`)) index += 1;
  return `n${index}`;
}

function defaultNodeDef(nodeId: string, nodeType: OrchNodeType): GraphNodeDef {
  const config = nodeType === 'HUMAN' ? { timeoutSeconds: 1800 } : undefined;
  return { nodeId, type: nodeType, ...(config ? { config } : {}) };
}

interface DesignerProps {
  graph: OrchestrationGraphEntity;
  onBack: () => void;
  onSaved: () => void;
}

function DesignerInner({ graph, onBack, onSaved }: DesignerProps) {
  const toast = useToast();
  const { screenToFlowPosition, fitView } = useReactFlow();
  const editable = graph.status === 'draft';

  const initial = useMemo<{ definition: GraphDefinition; parseError: string }>(() => {
    try {
      return { definition: parseDefinition(graph.graphDefinition || SKELETON_DEFINITION), parseError: '' };
    } catch (e) {
      return {
        definition: parseDefinition(SKELETON_DEFINITION),
        parseError: e instanceof Error ? e.message : '存量图定义解析失败',
      };
    }
  }, [graph.graphDefinition]);

  const [name, setName] = useState(graph.graphName);
  const [mode, setMode] = useState(initial.definition.mode ?? graph.orchestrationMode ?? 'PIPELINE');
  const [graphType, setGraphType] = useState(initial.definition.type ?? graph.graphType ?? 'AD_HOC');
  const [settings, setSettings] = useState<GraphSettings>({
    variablesText: initial.definition.variables ? JSON.stringify(initial.definition.variables, null, 2) : '',
    rootGoalId: initial.definition.rootGoalId ?? '',
    continueOnFailure: initial.definition.variables?.continueOnFailure === true ? 'true'
      : initial.definition.variables?.continueOnFailure === false ? 'false' : 'default',
  });
  // 执行回放（docs/design/46 FR-14）
  const [showReplay, setShowReplay] = useState(false);
  const [replayExecutions, setReplayExecutions] = useState<OrchestrationExecutionEntity[]>([]);
  const [replaySelectedId, setReplaySelectedId] = useState('');
  const [replayNodes, setReplayNodes] = useState<OrchestrationNodeExecution[]>([]);
  const [replayActive, setReplayActive] = useState(false);
  const initialFlow = useMemo(() => definitionToFlow(initial.definition), [initial.definition]);
  const [nodes, setNodes, onNodesChange] = useNodesState<OrchestrationNode>(initialFlow.nodes as OrchestrationNode[]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<OrchestrationEdge>(initialFlow.edges as OrchestrationEdge[]);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [showIssues, setShowIssues] = useState(false);
  const [showJson, setShowJson] = useState(false);
  const [jsonText, setJsonText] = useState('');
  const [jsonError, setJsonError] = useState('');
  const [saving, setSaving] = useState(false);
  const [catalogs, setCatalogs] = useState<Catalogs>({ roles: FALLBACK_ROLES, tools: [], agents: [], activeGraphs: [] });
  // 运行预览（FR-09）：SSE 事件按 nodeId 归因到画布节点
  const [showRun, setShowRun] = useState(false);
  const [running, setRunning] = useState(false);
  const [runInput, setRunInput] = useState('');
  const [runEvents, setRunEvents] = useState<OrchestrationRunEvent[]>([]);
  const [runResult, setRunResult] = useState<string | null>(null);
  const [approvalNodeId, setApprovalNodeId] = useState<string | null>(null);
  const [nodeStates, setNodeStates] = useState<Map<string, NodeRunState>>(new Map());
  const runAbortRef = useRef<AbortController | null>(null);
  const executionIdRef = useRef<string | null>(null);
  const toastFn = toast;

  useEffect(() => {
    if (initial.parseError) toastFn(initial.parseError, 'error');
    // 目录数据源并行加载；失败时保留兜底角色清单，不阻塞设计器
    listRoleCatalog().then(roles => setCatalogs(c => ({ ...c, roles: roles.length > 0 ? roles : c.roles })))
      .catch(() => { /* 目录接口不可用时使用前端兜底清单 */ });
    listToolCatalog().then(tools => setCatalogs(c => ({ ...c, tools })))
      .catch(() => { /* 工具目录留空，属性面板仍允许手选历史值 */ });
    listAgents(1, 100).then(page => setCatalogs(c => ({ ...c, agents: page.records ?? [] })))
      .catch(() => { /* Agent 列表不可用时 refId 回退手填场景由后端校验兜底 */ });
    // 已激活编排图（WFO-04 SUBGRAPH refId 下拉数据源；排除当前图自身）
    listGraphs('active').then(graphs => setCatalogs(c => ({
      ...c,
      activeGraphs: graphs.filter(g => g.id !== graph.id).map(g => ({ id: g.id, name: g.graphName })),
    }))).catch(() => { /* 图列表不可用时 SUBGRAPH refId 由后端 VL-13 校验兜底 */ });
  }, [initial.parseError, toastFn, graph.id]);

  const modeOption = MODE_OPTIONS.find(m => m.value === mode) ?? MODE_OPTIONS[0];

  // SUPERVISOR 徽标跟随模式与节点声明顺序（返回原引用避免无谓重渲染）
  useEffect(() => {
    const supId = supervisorNodeId({
      mode,
      nodes: nodes.map(n => n.data.def),
      edges: [],
    });
    setNodes(prev => {
      let changed = false;
      const next = prev.map(n => {
        const flag = n.id === supId;
        if (n.data.isSupervisor === flag) return n;
        changed = true;
        return { ...n, data: { ...n.data, isSupervisor: flag } };
      });
      return changed ? next : prev;
    });
  }, [mode, nodes, setNodes]);

  // 运行态同步到画布节点数据（无变化时返回原引用避免重渲染循环）
  useEffect(() => {
    setNodes(prev => {
      let changed = false;
      const next = prev.map(n => {
        const state = nodeStates.get(n.id);
        if ((n.data.state ?? undefined) === state) return n;
        changed = true;
        return { ...n, data: { ...n.data, state } };
      });
      return changed ? next : prev;
    });
  }, [nodeStates, setNodes]);

  // 卸载时中断在途事件流
  useEffect(() => () => { runAbortRef.current?.abort(); }, []);

  const currentDefinition = useMemo<GraphDefinition>(() => {
    let variables: Record<string, unknown> | undefined;
    if (settings.variablesText.trim()) {
      try {
        variables = JSON.parse(settings.variablesText) as Record<string, unknown>;
      } catch { /* 非法 JSON 保存时阻断，此处仅展示 */ }
    }
    if (variables && settings.continueOnFailure !== 'default') {
      variables.continueOnFailure = settings.continueOnFailure === 'true';
    }
    return flowToDefinition(nodes as unknown as DesignerFlowNode[], edges as unknown as DesignerFlowEdge[],
      { name, mode, type: graphType, variables, rootGoalId: settings.rootGoalId || undefined });
  }, [nodes, edges, name, mode, graphType, settings.variablesText, settings.rootGoalId, settings.continueOnFailure]);

  const issues = useMemo<ValidationIssue[]>(() => validateDefinition(currentDefinition), [currentDefinition]);
  const errorCount = issues.filter(i => i.level === 'ERROR').length;
  const warningCount = issues.length - errorCount;
  const selectedNode = nodes.find(n => n.id === selectedNodeId) ?? null;
  const selectedEdge = edges.find(e => e.id === selectedEdgeId) ?? null;

  const onUpdateNodeDef = useCallback((nodeId: string, updater: (def: GraphNodeDef) => GraphNodeDef) => {
    setNodes(nds => nds.map(n => (n.id === nodeId
      ? { ...n, data: { ...n.data, def: updater(n.data.def) } } : n)));
  }, [setNodes]);

  const onUpdateEdgeCondition = useCallback((edgeId: string, condition: string) => {
    setEdges(eds => eds.map(e => (e.id === edgeId
      ? { ...e, data: { condition: condition || undefined }, label: condition || undefined } : e)));
  }, [setEdges]);

  const onDeleteEdge = useCallback((edgeId: string) => {
    setEdges(eds => eds.filter(e => e.id !== edgeId));
    setSelectedEdgeId(null);
  }, [setEdges]);

  const onConnect = useCallback((connection: Connection) => {
    setEdges(eds => eds.concat({
      id: nextEdgeId(),
      source: connection.source,
      target: connection.target,
      sourceHandle: connection.sourceHandle,
      targetHandle: connection.targetHandle,
      data: {},
    } as OrchestrationEdge));
  }, [setEdges]);

  const handleNodesDelete = useCallback((deleted: OrchestrationNode[]) => {
    const ids = new Set(deleted.map(n => n.id));
    setEdges(eds => eds.filter(e => !ids.has(e.source) && !ids.has(e.target)));
    setSelectedNodeId(prev => (prev && ids.has(prev) ? null : prev));
  }, [setEdges]);

  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const onDrop = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    const nodeType = event.dataTransfer.getData(NODE_DND_MIME) as OrchNodeType;
    if (!nodeType || !NODE_CATALOG[nodeType]?.implemented) return;
    const position = screenToFlowPosition({ x: event.clientX, y: event.clientY });
    const nodeId = uniqueNodeId(new Set(nodes.map(n => n.id)));
    setNodes(nds => nds.concat({
      id: nodeId,
      type: 'orchestration',
      position,
      data: { def: defaultNodeDef(nodeId, nodeType), isSupervisor: false },
    }));
    setSelectedNodeId(nodeId);
    setSelectedEdgeId(null);
  }, [nodes, screenToFlowPosition, setNodes]);

  /** 一键整理布局：拓扑分层（层=最长路径深度），完成后自适应视野 */
  const handleAutoLayout = useCallback(() => {
    const positions = layeredLayout(
      nodes.map(n => ({ id: n.id })),
      edges.map(e => ({ fromNode: e.source, toNode: e.target }))
    );
    setNodes(prev => prev.map(n => {
      const position = positions.get(n.id);
      return position ? { ...n, position } : n;
    }));
    window.setTimeout(() => { void fitView({ padding: 0.15, duration: 200 }); }, 80);
  }, [nodes, edges, setNodes, fitView]);

  const openReplay = useCallback(async () => {
    setShowReplay(true);
    if (replayExecutions.length === 0) {
      try {
        setReplayExecutions(await listExecutions(graph.id));
      } catch { /* 执行历史加载失败不阻塞回放面板 */ }
    }
  }, [graph.id, replayExecutions.length]);

  const handleLoadReplay = useCallback(async () => {
    if (!replaySelectedId) return;
    try {
      const records = await listNodeExecutions(replaySelectedId);
      setReplayNodes(records);
      setNodeStates(() => {
        const map = new Map<string, NodeRunState>();
        for (const record of records) {
          if (record.status === 'SUCCEEDED' || record.status === 'FAILED' || record.status === 'RUNNING') {
            map.set(record.nodeId, record.status);
          }
        }
        return map;
      });
      setReplayActive(true);
    } catch (e) {
      toast(e instanceof Error ? e.message : '加载回放失败', 'error');
    }
  }, [replaySelectedId, toast]);

  const handleClearReplay = useCallback(() => {
    setNodeStates(new Map());
    setReplayNodes([]);
    setReplayActive(false);
  }, [setNodeStates]);

  const openJson = useCallback(() => {
    setJsonText(serializeDefinition(currentDefinition));
    setJsonError('');
    setShowJson(true);
  }, [currentDefinition]);

  const applyJson = useCallback(() => {
    try {
      const parsed = parseDefinition(jsonText);
      const flow = definitionToFlow(parsed);
      setNodes(flow.nodes as OrchestrationNode[]);
      setEdges(flow.edges as OrchestrationEdge[]);
      if (parsed.mode) setMode(parsed.mode);
      if (parsed.type) setGraphType(parsed.type);
      setSettings({
        variablesText: parsed.variables ? JSON.stringify(parsed.variables, null, 2) : '',
        rootGoalId: typeof parsed.rootGoalId === 'string' ? parsed.rootGoalId : '',
        continueOnFailure: parsed.variables?.continueOnFailure === true ? 'true'
          : parsed.variables?.continueOnFailure === false ? 'false' : 'default',
      });
      setSelectedNodeId(null);
      setSelectedEdgeId(null);
      setShowJson(false);
      toast('JSON 已应用到画布', 'success');
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : '解析失败');
    }
  }, [jsonText, setNodes, setEdges, toast]);

  /** 保存当前画布为图定义；notify 控制是否弹成功提示，返回是否保存成功 */
  const saveDefinition = useCallback(async (notify: boolean): Promise<boolean> => {
    if (settings.variablesText.trim()) {
      try {
        JSON.parse(settings.variablesText);
      } catch {
        toast('图变量 variables 不是合法 JSON', 'error');
        return false;
      }
    }
    if (errorCount > 0) {
      toast(`结构校验未通过（${errorCount} 项错误），请先处理后保存`, 'error');
      setShowIssues(true);
      return false;
    }
    setSaving(true);
    try {
      await updateGraph(graph.id, {
        name: name.trim() || graph.graphName,
        graphDefinition: serializeDefinition(currentDefinition),
        graphType,
        mode,
      });
      if (notify) toast('编排图已保存', 'success');
      onSaved();
      return true;
    } catch (e) {
      toast(e instanceof Error ? e.message : '保存失败', 'error');
      return false;
    } finally {
      setSaving(false);
    }
  }, [currentDefinition, errorCount, graph, mode, graphType, name, onSaved, settings.variablesText, toast]);

  const handleSave = useCallback(() => saveDefinition(true), [saveDefinition]);

  /** 流结束/中断兜底：error 事件帧通常不带 nodeId，后端异常中断也可能没有 graph_complete，
   * 把在途节点标记为失败，避免画布停留在"运行中" */
  const markInFlightFailed = useCallback(() => {
    setNodeStates(prev => {
      const next = new Map(prev);
      next.forEach((state, id) => { if (state === 'RUNNING' || state === 'APPROVAL') next.set(id, 'FAILED'); });
      return next;
    });
  }, []);

  /** SSE 事件 → 节点运行态/审批锚点/最终输出 */
  const applyRunEvent = useCallback((event: OrchestrationRunEvent) => {
    if (event.type === 'graph_start') {
      executionIdRef.current = typeof event.metadata?.executionId === 'string' ? event.metadata.executionId : null;
      setNodeStates(new Map());
    } else if ((event.type === 'node_start' || event.type === 'node_complete') && event.nodeId) {
      setNodeStates(prev => new Map(prev).set(event.nodeId as string,
        event.type === 'node_start' ? 'RUNNING' : 'SUCCEEDED'));
    } else if (event.type === 'approval_required') {
      if (event.nodeId) setNodeStates(prev => new Map(prev).set(event.nodeId as string, 'APPROVAL'));
      setApprovalNodeId(event.nodeId ?? null);
    } else if (event.type === 'approval_result') {
      setApprovalNodeId(null);
      setNodeStates(prev => {
        const next = new Map(prev);
        next.forEach((state, id) => { if (state === 'APPROVAL') next.set(id, 'RUNNING'); });
        return next;
      });
    } else if (event.type === 'graph_complete') {
      const status = String(event.metadata?.status ?? '');
      if (status === 'FAILED' || status === 'CANCELLED') {
        setNodeStates(prev => {
          const next = new Map(prev);
          next.forEach((state, id) => { if (state === 'RUNNING' || state === 'APPROVAL') next.set(id, 'FAILED'); });
          return next;
        });
      }
      if (event.metadata?.output != null) setRunResult(String(event.metadata.output));
      else if (event.errorMessage) setRunResult(event.errorMessage);
    } else if (event.type === 'error' && event.nodeId) {
      const failedNodeId = event.nodeId;
      setNodeStates(prev => new Map(prev).set(failedNodeId, 'FAILED'));
    }
  }, []);

  /** 运行预览：草稿图先保存再执行；已激活图直接运行已保存版本 */
  const handleRun = useCallback(async () => {
    if (running) return;
    if (editable) {
      const saved = await saveDefinition(true);
      if (!saved) return;
    }
    setRunEvents([]);
    setRunResult(null);
    setApprovalNodeId(null);
    setNodeStates(new Map());
    setShowRun(true);
    setRunning(true);
    executionIdRef.current = null;
    const controller = executeGraphStream(
      graph.id,
      runInput,
      event => {
        applyRunEvent(event);
        setRunEvents(prev => [...prev.slice(-99), event]);
      },
      () => { setRunning(false); markInFlightFailed(); },
      err => {
        setRunning(false);
        markInFlightFailed();
        setRunResult(err.message);
        toast(err.message, 'error');
      }
    );
    runAbortRef.current = controller;
  }, [applyRunEvent, editable, graph.id, markInFlightFailed, running, runInput, saveDefinition, toast]);

  const handleStopRun = useCallback(() => {
    runAbortRef.current?.abort();
    const executionId = executionIdRef.current;
    if (executionId) cancelExecution(executionId).catch(() => { /* 执行可能已结束，忽略 */ });
    setRunning(false);
  }, []);

  return (
    <div className="flex h-[calc(100vh-7rem)] flex-col">
      {/* 工具栏 */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <button onClick={onBack} title="返回列表"
          className="flex items-center gap-1.5 px-3 py-2 text-sm text-ink-300 hover:text-ink-100 border border-tech-500/20 rounded-lg transition-colors">
          <ArrowLeft className="h-4 w-4" />返回
        </button>
        <input type="text" value={name} onChange={e => setName(e.target.value)} aria-label="编排图名称"
          className="w-44 px-3 py-2 bg-ink-800/50 border border-tech-500/10 rounded-lg text-sm text-ink-100 outline-none focus:border-tech-500/30" />
        <CustomSelect value={mode} onChange={setMode} className="w-52"
          options={MODE_OPTIONS.map(m => ({ value: m.value, label: m.label }))} />
        <CustomSelect value={graphType} onChange={setGraphType} className="w-44"
          options={GRAPH_TYPE_OPTIONS} />
        <div className="ml-auto flex items-center gap-2">
          {!editable && <span className="rounded-full bg-ink-500/10 px-2 py-0.5 text-[10px] text-ink-400">已激活 · 只读</span>}
          <button onClick={handleAutoLayout} title="按拓扑分层整理节点布局"
            className="flex items-center gap-1.5 px-3 py-2 text-sm text-ink-300 hover:text-ink-100 border border-tech-500/20 rounded-lg transition-colors">
            <LayoutGrid className="h-4 w-4" />整理布局
          </button>
          <button onClick={handleRun} disabled={saving || running} title={editable ? '保存并运行（SSE 实时预览）' : '运行已保存版本（SSE 实时预览）'}
            className="flex items-center gap-1.5 px-3 py-2 text-sm text-tech-400 border border-tech-500/20 rounded-lg hover:bg-tech-500/10 transition-colors disabled:opacity-50">
            {running ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}运行
          </button>
          <button onClick={() => void openReplay()} title="按历史执行记录回放节点状态"
            className="flex items-center gap-1.5 px-3 py-2 text-sm text-ink-300 hover:text-ink-100 border border-tech-500/20 rounded-lg transition-colors">
            <History className="h-4 w-4" />回放
          </button>
          <button onClick={() => setShowIssues(v => !v)}
            className="flex items-center gap-1.5 px-3 py-2 text-sm text-ink-300 hover:text-ink-100 border border-tech-500/20 rounded-lg transition-colors">
            <ShieldCheck className="h-4 w-4" />校验
            {errorCount > 0 && <span className="rounded-full bg-cinnabar-500/15 px-1.5 text-[10px] text-cinnabar-400">{errorCount}</span>}
            {errorCount === 0 && warningCount > 0 && <span className="rounded-full bg-gold-500/15 px-1.5 text-[10px] text-gold-400">{warningCount}</span>}
          </button>
          <button onClick={openJson}
            className="flex items-center gap-1.5 px-3 py-2 text-sm text-ink-300 hover:text-ink-100 border border-tech-500/20 rounded-lg transition-colors">
            <Braces className="h-4 w-4" />JSON
          </button>
          <button onClick={handleSave} disabled={saving || !editable}
            title={editable ? '保存编排图' : '仅草稿状态的编排图可编辑'}
            className="flex items-center gap-1.5 px-4 py-2 btn-primary text-white text-sm rounded-lg disabled:opacity-50">
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}保存
          </button>
        </div>
      </div>

      {/* 校验结果面板 */}
      {showIssues && (
        <div className="mb-3 max-h-44 overflow-y-auto scrollbar-thin rounded-xl glass-dark p-3 text-xs">
          {issues.length === 0 ? (
            <p className="text-ink-400">结构校验通过，未发现问题。</p>
          ) : issues.map((issue, index) => (
            <button key={`${issue.ruleId}-${index}`}
              onClick={() => issue.nodeId && (setSelectedNodeId(issue.nodeId), setSelectedEdgeId(null))}
              className="flex w-full items-start gap-2 rounded px-1 py-1 text-left hover:bg-ink-800/50">
              <span className={`shrink-0 rounded px-1.5 text-[10px] ${issue.level === 'ERROR' ? 'bg-cinnabar-500/15 text-cinnabar-400' : 'bg-gold-500/15 text-gold-400'}`}>
                {issue.ruleId}
              </span>
              <span className="text-ink-300">{issue.message}</span>
            </button>
          ))}
        </div>
      )}

      <div className="flex min-h-0 flex-1 overflow-hidden rounded-xl glass-dark">
        <DesignerPalette mode={modeOption} />
        <div className="relative min-w-0 flex-1" onDrop={onDrop} onDragOver={onDragOver}>
          {!modeOption.edgesMatter && (
            <div className="pointer-events-none absolute left-1/2 top-3 z-10 -translate-x-1/2">
              <p className="flex items-center gap-1.5 rounded-lg bg-gold-500/15 px-3 py-1.5 text-[11px] text-gold-400">
                <TriangleAlert className="h-3.5 w-3.5" />{modeOption.hint}
              </p>
            </div>
          )}
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodesDelete={handleNodesDelete}
            onNodeClick={(_, node) => { setSelectedNodeId(node.id); setSelectedEdgeId(null); }}
            onEdgeClick={(_, edge) => { setSelectedEdgeId(edge.id); setSelectedNodeId(null); }}
            onPaneClick={() => { setSelectedNodeId(null); setSelectedEdgeId(null); }}
            deleteKeyCode={['Backspace', 'Delete']}
            snapToGrid
            snapGrid={[16, 16]}
            fitView
            proOptions={{ hideAttribution: true }}
          >
            <Background variant={BackgroundVariant.Dots} gap={16} />
            <Controls position="bottom-left" showInteractive={false} />
            {/* Minimap 节点色镜像 tailwind.config 令牌（tech/gold/cinnabar），SVG 属性不支持 CSS 变量；
                白底为 RF 默认样式，用 [&>svg] 覆写为暗色 */}
            <MiniMap pannable zoomable position="bottom-right"
              style={{ width: 160, height: 110 }}
              className="[&>svg]:!bg-ink-900 !rounded-lg !border !border-tech-500/20"
              maskColor="rgba(8, 18, 17, 0.72)"
              nodeColor={node => {
                const state = (node.data as DesignerNodeData).state;
                if (state === 'RUNNING') return '#00d4aa';
                if (state === 'SUCCEEDED') return '#22c55e';
                if (state === 'FAILED') return '#e05a4f';
                if (state === 'APPROVAL') return '#d4a853';
                return '#009b7d';
              }} />
          </ReactFlow>
        </div>
        <DesignerPropertyPanel
          selectedNode={selectedNode}
          selectedEdge={selectedEdge}
          settings={settings}
          mode={modeOption}
          catalogs={catalogs}
          graphId={graph.id}
          onUpdateNodeDef={onUpdateNodeDef}
          onUpdateEdgeCondition={onUpdateEdgeCondition}
          onDeleteEdge={onDeleteEdge}
          onSettingsChange={patch => setSettings(prev => ({ ...prev, ...patch }))}
        />
      </div>

      {showRun && (
        <DesignerRunConsole
          running={running}
          input={runInput}
          events={runEvents}
          result={runResult}
          approvalNodeId={approvalNodeId}
          editable={editable}
          onInputChange={setRunInput}
          onStart={handleRun}
          onStop={handleStopRun}
          onClose={() => { handleStopRun(); setShowRun(false); }}
        />
      )}

      {showReplay && (
        <DesignerReplayPanel
          executions={replayExecutions}
          selectedExecutionId={replaySelectedId}
          nodes={replayNodes}
          active={replayActive}
          onSelectExecution={setReplaySelectedId}
          onLoad={() => void handleLoadReplay()}
          onClear={handleClearReplay}
          onClose={() => setShowReplay(false)}
        />
      )}

      {showJson && (
        <DesignerJsonPanel value={jsonText} error={jsonError}
          onChange={next => {
            setJsonText(next);
            try {
              parseDefinition(next);
              setJsonError('');
            } catch (e) {
              setJsonError(e instanceof Error ? e.message : '解析失败');
            }
          }}
          onApply={applyJson} onClose={() => setShowJson(false)} />
      )}
    </div>
  );
}

export default function OrchestrationDesigner(props: DesignerProps) {
  return (
    <ReactFlowProvider>
      <DesignerInner {...props} />
    </ReactFlowProvider>
  );
}
