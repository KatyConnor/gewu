// 编排图预置模板库（docs/design/46 报告 FR-10）：
// 覆盖四种编排模式的典型场景，模板均为通过 VL 校验的合法图定义。
// AGENT 节点 refId 留空，运行时经图变量 modelProvider/modelName 兜底解析模型；
// 位置 x/y 为设计器画布坐标，随定义透传存储。
import type { GraphDefinition } from './orchestrationDesigner';

export interface OrchestrationTemplate {
  id: string;
  name: string;
  mode: string;
  description: string;
  definition: GraphDefinition;
}

/** 网格坐标小工具，避免模板里手写魔法数字 */
function grid(index: number, row = 0): { x: number; y: number } {
  return { x: 60 + index * 240, y: 60 + row * 170 };
}

const agent = (nodeId: string, roleCode: string, index: number, row = 0) => ({
  nodeId,
  type: 'AGENT' as const,
  roleCode,
  ...grid(index, row),
});

export const ORCHESTRATION_TEMPLATES: OrchestrationTemplate[] = [
  {
    id: 'approval-release',
    name: '审批发布链（含人工审批）',
    mode: 'PIPELINE',
    description: '需求梳理 → 人工审批 → 开发实现 → 提交工具，适合需要把关的交付流程',
    definition: {
      mode: 'PIPELINE',
      type: 'AD_HOC',
      nodes: [
        agent('n1', 'REQUIREMENT_PM', 0),
        { nodeId: 'n2', type: 'HUMAN', config: { timeoutSeconds: 1800 }, ...grid(1) },
        agent('n3', 'DEVELOPER', 2),
        { nodeId: 'n4', type: 'TOOL', refId: 'git_commit', config: { toolName: 'git_commit', outputVar: 'commitResult' }, ...grid(3) },
      ],
      edges: [
        { fromNode: 'n1', toNode: 'n2' },
        { fromNode: 'n2', toNode: 'n3' },
        { fromNode: 'n3', toNode: 'n4' },
      ],
    },
  },
  {
    id: 'review-router',
    name: '条件路由（审查通过/驳回）',
    mode: 'PIPELINE',
    description: '开发产出经审查 Agent 评估，按输出变量路由：通过则汇合，驳回则进入修复分支后汇合',
    definition: {
      mode: 'PIPELINE',
      type: 'AD_HOC',
      nodes: [
        agent('n1', 'DEVELOPER', 0),
        agent('n2', 'CODE_REVIEW', 1),
        { nodeId: 'n3', type: 'ROUTER', ...grid(2) },
        agent('n4', 'DEVELOPER', 3, 1),
        { nodeId: 'n5', type: 'MERGE', config: { strategy: 'json_merge' }, ...grid(4, 0) },
      ],
      edges: [
        { fromNode: 'n1', toNode: 'n2' },
        { fromNode: 'n2', toNode: 'n3' },
        { fromNode: 'n3', toNode: 'n5', condition: "var:decision == 'PASS'" },
        { fromNode: 'n3', toNode: 'n4', condition: 'else' },
        { fromNode: 'n4', toNode: 'n5' },
      ],
    },
  },
  {
    id: 'parallel-merge',
    name: '并行扇出与汇聚',
    mode: 'PIPELINE',
    description: '一份需求并行交给开发与测试视角处理，MERGE 汇聚后输出综合结论',
    definition: {
      mode: 'PIPELINE',
      type: 'AD_HOC',
      nodes: [
        agent('n1', 'REQUIREMENT_PM', 0),
        { nodeId: 'n2', type: 'PARALLEL', ...grid(1) },
        agent('n3', 'DEVELOPER', 2, 0),
        agent('n4', 'TEST_ENGINEER', 2, 1),
        { nodeId: 'n5', type: 'MERGE', ...grid(3) },
      ],
      edges: [
        { fromNode: 'n1', toNode: 'n2' },
        { fromNode: 'n2', toNode: 'n3' },
        { fromNode: 'n2', toNode: 'n4' },
        { fromNode: 'n3', toNode: 'n5' },
        { fromNode: 'n4', toNode: 'n5' },
      ],
    },
  },
  {
    id: 'research-swarm',
    name: '调研接力（群体协作）',
    mode: 'SWARM',
    description: '三个 Agent 按声明顺序接力，运行时由输出中的 HANDOFF/FINISH 指令决定走向，边不参与执行',
    definition: {
      mode: 'SWARM',
      type: 'AD_HOC',
      nodes: [
        agent('a1', 'REQUIREMENT_PM', 0),
        agent('a2', 'ARCHITECT', 1),
        agent('a3', 'DOC_ENGINEER', 2),
      ],
      edges: [],
    },
  },
  {
    id: 'supervisor-dispatch',
    name: '监督分派（监督者模式）',
    mode: 'SUPERVISOR',
    description: '首个 AGENT 节点为监督者，其余专家按声明顺序依次接手，边不参与执行',
    definition: {
      mode: 'SUPERVISOR',
      type: 'AD_HOC',
      nodes: [
        agent('s0', 'ARCHITECT', 0),
        agent('s1', 'DEVELOPER', 1),
        agent('s2', 'TEST_ENGINEER', 2),
        agent('s3', 'SRE', 3),
      ],
      edges: [],
    },
  },
  {
    id: 'debate-consensus',
    name: '辩论共识（Debate）',
    mode: 'DEBATE',
    description: '两个 Agent 各自给出方案并行辩论，MERGE 节点作为裁判汇总裁决',
    definition: {
      mode: 'DEBATE',
      type: 'AD_HOC',
      nodes: [
        agent('d1', 'ARCHITECT', 0),
        agent('d2', 'SECURITY_AUDIT', 0, 1),
        { nodeId: 'd3', type: 'MERGE', ...grid(2) },
      ],
      edges: [],
    },
  },
];

export function findTemplate(id: string): OrchestrationTemplate | undefined {
  return ORCHESTRATION_TEMPLATES.find(template => template.id === id);
}
