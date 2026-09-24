// 编排图设计器核心库：图定义类型、画布双向转换、客户端结构校验（VL 规则）。
// 规则与后端 GraphDefinitionValidator 对齐（docs/design/46 报告 §7.5），
// 后端保存/执行前仍会做权威校验，此处用于画布实时提示。

// ==================== 图定义类型（对齐 OrchestrationGraph/GraphNode/GraphEdge） ====================

export type OrchNodeType =
  | 'AGENT' | 'TOOL' | 'HUMAN' | 'ROUTER'
  | 'PARALLEL' | 'MERGE' | 'PLAN' | 'SUBGRAPH';

/** 图定义节点（坐标 x/y 为设计器画布位置，引擎不消费） */
export interface GraphNodeDef {
  nodeId: string;
  type?: OrchNodeType;
  refId?: string;
  roleCode?: string;
  executionMode?: string;
  config?: Record<string, unknown>;
  inputs?: Record<string, unknown>;
  x?: number;
  y?: number;
}

export interface GraphEdgeDef {
  fromNode: string;
  toNode: string;
  condition?: string;
}

export interface GraphDefinition {
  name?: string;
  mode?: string;
  type?: string;
  nodes: GraphNodeDef[];
  edges: GraphEdgeDef[];
  variables?: Record<string, unknown>;
  rootGoalId?: string;
}

// ==================== 画布模型（React Flow 数据结构） ====================

/** 节点运行态（SSE 事件驱动，仅设计器运行预览使用） */
export type NodeRunState = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'APPROVAL';

/** 编排执行 SSE 事件（与 lib/orchestration.ts 的 onEvent 参数结构一致） */
export interface OrchestrationRunEvent {
  type: string;
  content?: string;
  reasoning?: string;
  nodeId?: string;
  errorMessage?: string;
  metadata?: Record<string, unknown>;
}

export interface DesignerNodeData extends Record<string, unknown> {
  def: GraphNodeDef;
  /** 模式派生标记：SUPERVISOR 模式下首个 AGENT 节点 */
  isSupervisor?: boolean;
  /** 运行态着色（undefined = 未运行） */
  state?: NodeRunState;
}

export interface DesignerFlowNode {
  id: string;
  type: 'orchestration';
  position: { x: number; y: number };
  data: DesignerNodeData;
}

export interface DesignerFlowEdge {
  id: string;
  source: string;
  target: string;
  label?: string;
  data: { condition?: string };
}

// ==================== 节点目录 ====================

export const NODE_CATALOG: Record<OrchNodeType, {
  label: string;
  description: string;
  implemented: boolean;
}> = {
  AGENT: { label: 'Agent 节点', description: '委派给某个 Agent 执行，支持角色与执行模式', implemented: true },
  TOOL: { label: '工具节点', description: '直接调用工具（无 LLM 推理）', implemented: true },
  HUMAN: { label: '人工审批', description: '阻塞等待人工审批后继续', implemented: true },
  ROUTER: { label: '条件路由', description: '按出边 condition 表达式选择分支', implemented: true },
  PARALLEL: { label: '并行扇出', description: '同时触发全部出边分支', implemented: true },
  MERGE: { label: '汇聚合并', description: '等待全部分支到齐后合并产出', implemented: true },
  PLAN: { label: '动态规划', description: '输入经 GoalPlanner 拆解为子计划执行', implemented: true },
  SUBGRAPH: { label: '嵌套子图', description: '引擎尚未实现，当前按 Agent 处理', implemented: false },
};

// ==================== 模式与枚举常量 ====================

export interface ModeOption {
  value: string;
  label: string;
  /** 该模式下边结构是否参与执行 */
  edgesMatter: boolean;
  hint: string;
}

export const MODE_OPTIONS: ModeOption[] = [
  { value: 'PIPELINE', label: '流水线（Pipeline）', edgesMatter: true, hint: '按边顺序执行，支持条件分支、并行汇聚、人工审批与动态规划' },
  { value: 'SWARM', label: '群体协作（Swarm）', edgesMatter: false, hint: 'Agent 输出 HANDOFF/FINISH 指令接力，按声明顺序防环传递，边不参与执行' },
  { value: 'SUPERVISOR', label: '监督者（Supervisor）', edgesMatter: false, hint: '首个 AGENT 节点为监督者，其余为专家按声明顺序分派，边不参与执行' },
  { value: 'DEBATE', label: '辩论共识（Debate）', edgesMatter: true, hint: '全部 AGENT 并行辩论，由 MERGE/ROUTER 节点作裁判汇总' },
];

export const EXECUTION_MODE_OPTIONS = [
  { value: 'REACT', label: 'ReAct（推理+行动循环）' },
  { value: 'PLAN_EXECUTE', label: 'Plan-Execute（先规划后执行）' },
  { value: 'REFLEXION', label: 'Reflexion（反思迭代）' },
  { value: 'TOOL_PARALLEL', label: 'Tool-Parallel（工具并行）' },
];

export const GRAPH_TYPE_OPTIONS = [
  { value: 'AD_HOC', label: '临时图（AD_HOC）' },
  { value: 'SDLC_PIPELINE', label: 'SDLC 流水线' },
  { value: 'GOAL_DECOMPOSED', label: '目标分解图' },
  { value: 'TEMPLATE', label: '可复用模板' },
];

/** 角色目录接口不可用时的兜底清单（与 RoleRegistry 内置 12 角色一致） */
export const FALLBACK_ROLES = [
  { roleCode: 'DATABASE_DESIGN', roleName: '数据库设计', sdlcPhase: 'DESIGN' },
  { roleCode: 'ARCHITECT', roleName: '架构师', sdlcPhase: 'DESIGN' },
  { roleCode: 'DEVELOPER', roleName: '开发者', sdlcPhase: 'DEVELOP' },
  { roleCode: 'DEVOPS', roleName: 'DevOps工程师', sdlcPhase: 'DEPLOY' },
  { roleCode: 'DOC_ENGINEER', roleName: '文档工程师', sdlcPhase: 'OPS' },
  { roleCode: 'QA', roleName: 'QA工程师', sdlcPhase: 'TEST' },
  { roleCode: 'REFACTOR', roleName: '重构工程师', sdlcPhase: 'DEVELOP' },
  { roleCode: 'REQUIREMENT_PM', roleName: '需求PM', sdlcPhase: 'REQUIREMENT' },
  { roleCode: 'SECURITY_AUDIT', roleName: '安全审计', sdlcPhase: 'REVIEW' },
  { roleCode: 'SRE', roleName: 'SRE工程师', sdlcPhase: 'OPS' },
  { roleCode: 'TEST_ENGINEER', roleName: '测试工程师', sdlcPhase: 'TEST' },
  { roleCode: 'CODE_REVIEW', roleName: '代码审查', sdlcPhase: 'REVIEW' },
];

// ==================== 双向转换 ====================

const GRID_COL_WIDTH = 260;
const GRID_ROW_HEIGHT = 140;
const GRID_COLS = 4;

/** 无坐标节点按网格排布（存量图兼容兜底） */
export function gridPosition(index: number): { x: number; y: number } {
  return {
    x: (index % GRID_COLS) * GRID_COL_WIDTH + 60,
    y: Math.floor(index / GRID_COLS) * GRID_ROW_HEIGHT + 60,
  };
}

/** 图定义 → 画布节点/边 */
export function definitionToFlow(definition: GraphDefinition): {
  nodes: DesignerFlowNode[];
  edges: DesignerFlowEdge[];
} {
  const nodes: DesignerFlowNode[] = definition.nodes.map((def, index) => ({
    id: def.nodeId,
    type: 'orchestration' as const,
    position: { x: def.x ?? gridPosition(index).x, y: def.y ?? gridPosition(index).y },
    data: { def },
  }));
  const edges: DesignerFlowEdge[] = definition.edges.map((edge, index) => ({
    id: `e${index}-${edge.fromNode}-${edge.toNode}`,
    source: edge.fromNode,
    target: edge.toNode,
    label: edge.condition || undefined,
    data: { condition: edge.condition },
  }));
  return { nodes, edges };
}

/** 画布节点/边 → 图定义（保存与 JSON 视图共用，position 回写 x/y） */
export function flowToDefinition(
  nodes: DesignerFlowNode[],
  edges: DesignerFlowEdge[],
  settings: Pick<GraphDefinition, 'name' | 'mode' | 'type' | 'variables' | 'rootGoalId'>
): GraphDefinition {
  return {
    ...settings,
    nodes: nodes.map(node => ({
      ...node.data.def,
      x: Math.round(node.position.x),
      y: Math.round(node.position.y),
    })),
    edges: edges.map(edge => ({
      fromNode: edge.source,
      toNode: edge.target,
      ...(edge.data?.condition ? { condition: edge.data.condition } : {}),
    })),
  };
}

/** 解析图定义 JSON 文本，语法错误抛出可读异常 */
export function parseDefinition(text: string): GraphDefinition {
  const parsed: unknown = JSON.parse(text);
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('图定义必须是 JSON 对象');
  }
  const obj = parsed as Record<string, unknown>;
  return {
    name: typeof obj.name === 'string' ? obj.name : undefined,
    mode: typeof obj.mode === 'string' ? obj.mode : undefined,
    type: typeof obj.type === 'string' ? obj.type : undefined,
    nodes: Array.isArray(obj.nodes) ? (obj.nodes as GraphNodeDef[]) : [],
    edges: Array.isArray(obj.edges) ? (obj.edges as GraphEdgeDef[]) : [],
    variables: (obj.variables && typeof obj.variables === 'object' && !Array.isArray(obj.variables))
      ? (obj.variables as Record<string, unknown>) : undefined,
    rootGoalId: typeof obj.rootGoalId === 'string' ? obj.rootGoalId : undefined,
  };
}

export function serializeDefinition(definition: GraphDefinition): string {
  return JSON.stringify(definition, null, 2);
}

/** SUPERVISOR 模式下首个 AGENT 节点的 nodeId（监督者徽标依据） */
export function supervisorNodeId(definition: GraphDefinition): string | null {
  const firstAgent = definition.nodes.find(node => (node.type ?? 'AGENT') === 'AGENT');
  return definition.mode === 'SUPERVISOR' ? (firstAgent?.nodeId ?? null) : null;
}

// ==================== 客户端结构校验（VL 规则子集） ====================

export interface ValidationIssue {
  ruleId: string;
  level: 'ERROR' | 'WARNING';
  nodeId?: string;
  edgeIndex?: number;
  message: string;
}

const VAR_REF = /\$\{var\.([A-Za-z0-9_.-]+)\}/g;
const HUMAN_TIMEOUT_MAX = 86400;

export function validateDefinition(definition: GraphDefinition): ValidationIssue[] {
  const issues: ValidationIssue[] = [];
  const nodes = definition.nodes ?? [];
  const edges = definition.edges ?? [];
  const nodeIds = new Set<string>();

  for (const node of nodes) {
    if (!node.nodeId || !node.nodeId.trim()) {
      issues.push({ ruleId: 'VL-01', level: 'ERROR', message: '存在缺少节点 ID 的节点' });
    } else if (nodeIds.has(node.nodeId)) {
      issues.push({ ruleId: 'VL-01', level: 'ERROR', nodeId: node.nodeId, message: `节点 ID 重复: ${node.nodeId}` });
    } else {
      nodeIds.add(node.nodeId);
    }
  }
  edges.forEach((edge, index) => {
    if (!nodeIds.has(edge.fromNode) || !nodeIds.has(edge.toNode)) {
      issues.push({ ruleId: 'VL-02', level: 'ERROR', edgeIndex: index, message: `边 ${index} 引用了不存在的节点（悬空边）` });
    }
  });

  const resolvable = new Set<string>(['input']);
  nodeIds.forEach(id => resolvable.add(id));
  Object.keys(definition.variables ?? {}).forEach(key => resolvable.add(key));
  for (const node of nodes) {
    validateNode(node, resolvable, issues);
  }
  validateModeRules(definition, issues);
  return issues;
}

function validateNode(node: GraphNodeDef, resolvable: Set<string>, issues: ValidationIssue[]): void {
  const config = node.config ?? {};
  if (node.type === 'TOOL' && !String(config.toolName ?? '').trim()) {
    issues.push({ ruleId: 'VL-06', level: 'ERROR', nodeId: node.nodeId, message: `工具节点 ${node.nodeId} 缺少 config.toolName` });
  }
  if (node.type === 'HUMAN' && config.timeoutSeconds != null) {
    const timeout = Number(config.timeoutSeconds);
    if (!Number.isFinite(timeout) || timeout < 1 || timeout > HUMAN_TIMEOUT_MAX) {
      issues.push({ ruleId: 'VL-08', level: 'WARNING', nodeId: node.nodeId, message: `审批超时 timeoutSeconds 应在 [1, 86400] 内` });
    }
  }
  if (node.type === 'AGENT' && config.outputSchema != null) {
    const schema = config.outputSchema;
    const valid = typeof schema === 'object'
      || (typeof schema === 'string' && (schema.includes(',') || isParsableJson(schema)));
    if (!valid) {
      issues.push({ ruleId: 'VL-10', level: 'ERROR', nodeId: node.nodeId, message: `节点 ${node.nodeId} 的 outputSchema 不是合法 JSON 或逗号分隔字段串` });
    }
  }
  const refTexts = [
    ...Object.values(node.inputs ?? {}).map(String),
    ...[config.arguments != null ? String(config.arguments) : ''],
  ];
  for (const text of refTexts) {
    for (const match of Array.from(text.matchAll(VAR_REF))) {
      if (!resolvable.has(match[1])) {
        issues.push({ ruleId: 'VL-09', level: 'WARNING', nodeId: node.nodeId, message: `变量 \${var.${match[1]}} 无法解析（未在图变量或前驱节点产出中声明）` });
      }
    }
  }
}

function isParsableJson(text: string): boolean {
  try {
    const value: unknown = JSON.parse(text);
    return value != null && typeof value === 'object';
  } catch {
    return false;
  }
}

function validateModeRules(definition: GraphDefinition, issues: ValidationIssue[]): void {
  const nodes = definition.nodes ?? [];
  const edges = definition.edges ?? [];
  const agentCount = nodes.filter(n => (n.type ?? 'AGENT') === 'AGENT').length;

  if (definition.mode === 'SWARM') {
    if (agentCount === 0) issues.push({ ruleId: 'VL-04', level: 'ERROR', message: '群体协作（SWARM）模式至少需要 1 个 AGENT 节点' });
    if (edges.length > 0) issues.push({ ruleId: 'VL-12', level: 'WARNING', message: 'SWARM 模式忽略边结构：路由由 Agent 输出的 HANDOFF/FINISH 指令决定' });
    return;
  }
  if (definition.mode === 'SUPERVISOR') {
    if (agentCount === 0) issues.push({ ruleId: 'VL-05', level: 'ERROR', message: '监督者（SUPERVISOR）模式至少需要 1 个 AGENT 节点，第一个 AGENT 节点为监督者' });
    if (edges.length > 0) issues.push({ ruleId: 'VL-12', level: 'WARNING', message: 'SUPERVISOR 模式忽略边结构：按节点声明顺序串行分派' });
    return;
  }
  if (definition.mode === 'DEBATE') {
    const hasJudge = nodes.some(n => n.type === 'MERGE' || n.type === 'ROUTER');
    if (!hasJudge) issues.push({ ruleId: 'VL-11', level: 'WARNING', message: '辩论共识（DEBATE）模式建议包含 MERGE/ROUTER 节点作为裁判' });
    return;
  }
  // PIPELINE 及未知模式：结构完整性
  if (nodes.length === 0) {
    issues.push({ ruleId: 'VL-03', level: 'WARNING', message: '图为空，没有任何节点' });
    return;
  }
  const targets = new Set(edges.map(e => e.toNode));
  if (!nodes.some(n => n.nodeId && !targets.has(n.nodeId))) {
    issues.push({ ruleId: 'VL-03', level: 'WARNING', message: '未找到无入边的起始节点，执行将从第一个节点回退开始' });
  }
  if (hasCycle(nodes.map(n => n.nodeId), edges)) {
    const first = nodes.find(n => n.nodeId)?.nodeId;
    issues.push({ ruleId: 'VL-03', level: 'ERROR', nodeId: first, message: '图存在环，PIPELINE 执行将无法终止' });
  }
  for (let index = 0; index < edges.length; index++) {
    const edge = edges[index];
    const sourceNode = nodes.find(n => n.nodeId === edge.fromNode);
    if (sourceNode?.type === 'ROUTER' && !(edge.condition ?? '').trim()) {
      issues.push({ ruleId: 'VL-03', level: 'ERROR', nodeId: edge.fromNode, edgeIndex: index, message: `路由节点 ${edge.fromNode} 的出边缺少 condition 条件表达式` });
    }
  }
  const outDegree = new Map<string, number>();
  const inDegree = new Map<string, number>();
  for (const edge of edges) {
    outDegree.set(edge.fromNode, (outDegree.get(edge.fromNode) ?? 0) + 1);
    inDegree.set(edge.toNode, (inDegree.get(edge.toNode) ?? 0) + 1);
  }
  for (const node of nodes) {
    if (node.type === 'PARALLEL' && (outDegree.get(node.nodeId) ?? 0) < 2) {
      issues.push({ ruleId: 'VL-03', level: 'WARNING', nodeId: node.nodeId, message: `并行节点 ${node.nodeId} 出边少于 2 条，无实际并行分支` });
    }
    if (node.type === 'MERGE' && (inDegree.get(node.nodeId) ?? 0) < 2) {
      issues.push({ ruleId: 'VL-03', level: 'WARNING', nodeId: node.nodeId, message: `汇聚节点 ${node.nodeId} 入边少于 2 条，无实际汇聚分支` });
    }
  }
}

function hasCycle(nodeIds: string[], edges: GraphEdgeDef[]): boolean {
  const adjacency = new Map<string, string[]>();
  for (const edge of edges) {
    adjacency.set(edge.fromNode, [...(adjacency.get(edge.fromNode) ?? []), edge.toNode]);
  }
  const visiting = new Set<string>();
  const visited = new Set<string>();
  const walk = (nodeId: string): boolean => {
    visiting.add(nodeId);
    for (const next of adjacency.get(nodeId) ?? []) {
      if (visiting.has(next)) return true;
      if (!visited.has(next) && walk(next)) return true;
    }
    visiting.delete(nodeId);
    visited.add(nodeId);
    return false;
  };
  return nodeIds.some(id => id && !visited.has(id) && walk(id));
}

/** 画布连线 → 图定义边（新边默认无条件） */
export function connectionToEdgeDef(connection: { source: string; target: string }): GraphEdgeDef {
  return { fromNode: connection.source, toNode: connection.target };
}

let edgeSeq = 0;
export function nextEdgeId(): string {
  edgeSeq += 1;
  return `edge-${Date.now()}-${edgeSeq}`;
}

// ==================== 自动布局（拓扑分层，FR-11） ====================

const LAYOUT_COL_WIDTH = 260;
const LAYOUT_ROW_HEIGHT = 170;
const LAYOUT_ORIGIN = 60;

/**
 * 拓扑分层布局：层 = 自起始节点出发的最长路径深度，层内自上而下排布。
 * 对环与悬空边安全：入度迭代有限步，未定层节点归入最深层的下一列，不抛错。
 */
export function layeredLayout(
  nodes: { id: string }[],
  edges: { fromNode: string; toNode: string }[]
): Map<string, { x: number; y: number }> {
  const ids = nodes.map(node => node.id).filter(Boolean);
  const idSet = new Set(ids);
  const inDegree = new Map<string, number>();
  const adjacency = new Map<string, string[]>();
  ids.forEach(id => {
    inDegree.set(id, 0);
    adjacency.set(id, []);
  });
  for (const edge of edges) {
    if (!idSet.has(edge.fromNode) || !idSet.has(edge.toNode)) continue;
    adjacency.get(edge.fromNode)?.push(edge.toNode);
    inDegree.set(edge.toNode, (inDegree.get(edge.toNode) ?? 0) + 1);
  }

  const level = new Map<string, number>();
  const queue: string[] = [];
  inDegree.forEach((degree, id) => {
    if (degree === 0) {
      queue.push(id);
      level.set(id, 0);
    }
  });
  const processed = new Set<string>();
  while (queue.length > 0) {
    const id = queue.shift() as string;
    if (processed.has(id)) continue;
    processed.add(id);
    const nextLevel = (level.get(id) ?? 0) + 1;
    for (const next of adjacency.get(id) ?? []) {
      level.set(next, Math.max(level.get(next) ?? 0, nextLevel));
      const remaining = (inDegree.get(next) ?? 1) - 1;
      inDegree.set(next, remaining);
      if (remaining === 0 && !processed.has(next)) queue.push(next);
    }
  }

  let deepest = 0;
  level.forEach(value => { deepest = Math.max(deepest, value); });
  const byLevel = new Map<number, string[]>();
  ids.forEach(id => {
    const nodeLevel = level.get(id) ?? deepest + 1;
    byLevel.set(nodeLevel, [...(byLevel.get(nodeLevel) ?? []), id]);
  });
  const positions = new Map<string, { x: number; y: number }>();
  byLevel.forEach((levelIds, nodeLevel) => {
    levelIds.forEach((id, index) => {
      positions.set(id, {
        x: LAYOUT_ORIGIN + nodeLevel * LAYOUT_COL_WIDTH,
        y: LAYOUT_ORIGIN + index * LAYOUT_ROW_HEIGHT,
      });
    });
  });
  return positions;
}
