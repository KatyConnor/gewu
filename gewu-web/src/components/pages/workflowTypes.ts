// 工作流画布 - 类型定义与节点配置
// 节点目录对齐 53 号节点体系 v2（7 大类）与后端 WorkflowNodeHandlerRegistry 实际注册表——
// 仅收录引擎已实现的类型，configFields 键名与后端 Handler config 逐一同源（校验同源原则）。

export type NodeType =
  // 触发器（5）
  | 'manual-trigger' | 'schedule-trigger' | 'webhook-trigger' | 'event-trigger' | 'upstream-trigger'
  // 人工任务（2）
  | 'task' | 'approval'
  // 逻辑控制（7）
  | 'condition' | 'switch' | 'decision' | 'loop' | 'parallel' | 'join' | 'filter'
  // AI 能力（4）
  | 'llm' | 'agent' | 'orchestration' | 'knowledge'
  // 数据处理（3）
  | 'transform' | 'json-parse' | 'set-variable'
  // 集成对接（1+）
  | 'http-request'
  // 流程控制与事件（6）
  | 'delay' | 'receive-message' | 'respond' | 'event-wait' | 'sub-workflow'
  // 终结事件（3）
  | 'return' | 'error-end' | 'terminate-end';

export type NodeCategory =
  | 'trigger' | 'task' | 'logic' | 'ai' | 'data' | 'integration' | 'flow' | 'terminal';

export interface NodePort {
  id: string;
  label: string;
  type: 'input' | 'output';
}

export interface NodeTypeConfig {
  type: NodeType;
  label: string;
  category: NodeCategory;
  icon: string; // lucide icon name
  color: string;
  bgColor: string;
  borderColor: string;
  description: string;
  inputs: NodePort[];
  outputs: NodePort[];
  configFields: ConfigField[];
}

export interface ConfigField {
  key: string;
  label: string;
  type: 'text' | 'textarea' | 'select' | 'number' | 'json';
  placeholder?: string;
  options?: { value: string; label: string }[];
  defaultValue?: string;
  hint?: string;
  /** 后端 Handler requiredConfigFields 对应项——保存前同源校验 */
  required?: boolean;
}

export interface WorkflowNode {
  id: string;
  type: NodeType;
  label: string;
  x: number;
  y: number;
  config: Record<string, string>;
}

export interface WorkflowConnection {
  id: string;
  from: string;
  fromPort: string;
  to: string;
  toPort: string;
  label?: string;
}

// --- Category Config ---

export const categoryConfig: Record<NodeCategory, { label: string; color: string; bgColor: string }> = {
  trigger:     { label: '触发器', color: 'text-amber-400', bgColor: 'bg-amber-500/10' },
  task:        { label: '人工任务', color: 'text-rose-400', bgColor: 'bg-rose-500/10' },
  logic:       { label: '逻辑控制', color: 'text-purple-400', bgColor: 'bg-purple-500/10' },
  ai:          { label: 'AI 能力', color: 'text-tech-400', bgColor: 'bg-tech-500/10' },
  data:        { label: '数据处理', color: 'text-cyan-400', bgColor: 'bg-cyan-500/10' },
  integration: { label: '集成对接', color: 'text-blue-400', bgColor: 'bg-blue-500/10' },
  flow:        { label: '流程控制与事件', color: 'text-orange-400', bgColor: 'bg-orange-500/10' },
  terminal:    { label: '终结事件', color: 'text-red-400', bgColor: 'bg-red-500/10' },
};

// --- Node Type Definitions ---
// configFields 键名与后端 Handler config 逐一同源：
//   condition/switch→expression；task→assigneeId/assigneeRole/timeoutHours/timeoutAction；
//   approval→approverIds/approvalMode/approveRatio；decision→decisions[]+default；
//   event-wait→eventTypes[]；receive-message→messageKey；respond→payload；
//   llm→modelProvider/promptTemplate；agent→agentId/taskTemplate；
//   orchestration→graphId/inputTemplate；knowledge→knowledgeBaseId/queryTemplate/topK。

const timeoutActionOptions = [
  { value: 'approve', label: '自动通过' },
  { value: 'reject', label: '自动驳回' },
  { value: 'escalate', label: '升级处理' },
];

export const nodeTypes: NodeTypeConfig[] = [
  // ===== 触发器（5） =====
  {
    type: 'manual-trigger', label: '手动触发', category: 'trigger',
    icon: 'Hand', color: 'text-amber-400', bgColor: 'bg-amber-500/10', borderColor: 'border-amber-500/30',
    description: '手动启动工作流（发起人或 API）',
    inputs: [],
    outputs: [{ id: 'out', label: '开始', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '手动触发', defaultValue: '手动触发' },
    ],
  },
  {
    type: 'schedule-trigger', label: '定时触发', category: 'trigger',
    icon: 'Clock', color: 'text-amber-400', bgColor: 'bg-amber-500/10', borderColor: 'border-amber-500/30',
    description: '按 Cron 计划自动触发（P3）',
    inputs: [],
    outputs: [{ id: 'out', label: '开始', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '定时触发', defaultValue: '定时触发' },
      { key: 'cronExpr', label: 'Cron 表达式', type: 'text', placeholder: '0 0 9 * * *',
        hint: '6 位（Spring CronExpression）；实际调度以流程详情的定时配置为准', defaultValue: '0 0 9 * * *' },
      { key: 'timezone', label: '时区', type: 'text', placeholder: 'Asia/Shanghai', defaultValue: 'Asia/Shanghai' },
      { key: 'inputTemplate', label: '输入模板', type: 'textarea', placeholder: '定时巡检输入' },
    ],
  },
  {
    type: 'webhook-trigger', label: 'Webhook 触发', category: 'trigger',
    icon: 'Globe', color: 'text-amber-400', bgColor: 'bg-amber-500/10', borderColor: 'border-amber-500/30',
    description: '匿名 HTTP 端点触发（P3，token 即凭证）',
    inputs: [],
    outputs: [{ id: 'out', label: '开始', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: 'Webhook 触发', defaultValue: 'Webhook 触发' },
      { key: 'hint', label: 'Webhook 地址与 token', type: 'text', placeholder: '发布后在流程详情中生成',
        hint: '触发凭证在流程详情的 Webhook 配置中管理（token 一次性明文）' },
    ],
  },
  {
    type: 'event-trigger', label: '事件触发', category: 'trigger',
    icon: 'Zap', color: 'text-amber-400', bgColor: 'bg-amber-500/10', borderColor: 'border-amber-500/30',
    description: '订阅事件到达后自动发起（P3）',
    inputs: [],
    outputs: [{ id: 'out', label: '开始', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '事件触发', defaultValue: '事件触发' },
      { key: 'eventType', label: '事件类型', type: 'text', placeholder: 'order.paid', required: true,
        hint: '事件交付（POST /workflows/events/{type}）命中后自动发起' },
    ],
  },
  {
    type: 'upstream-trigger', label: '上游触发', category: 'trigger',
    icon: 'ArrowDownUp', color: 'text-amber-400', bgColor: 'bg-amber-500/10', borderColor: 'border-amber-500/30',
    description: '上游流程完成后自动发起（P3 触发链）',
    inputs: [],
    outputs: [{ id: 'out', label: '开始', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '上游触发', defaultValue: '上游触发' },
      { key: 'upstreamWorkflowId', label: '上游工作流 ID', type: 'text', placeholder: '01M3...', required: true,
        hint: '上游实例成功完成后，以其最终输出作为本流程输入' },
    ],
  },

  // ===== 人工任务（2） =====
  {
    type: 'task', label: '人工办理', category: 'task',
    icon: 'UserCheck', color: 'text-rose-400', bgColor: 'bg-rose-500/10', borderColor: 'border-rose-500/30',
    description: '挂起等待办理人提交（BPM User Task）',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '人工办理', defaultValue: '人工办理' },
      { key: 'assigneeId', label: '指派办理人 ID', type: 'text', placeholder: '用户 ULID（留空=任意登录用户可办）' },
      { key: 'assigneeRole', label: '指派角色', type: 'text', placeholder: 'admin（角色成员可办）' },
      { key: 'timeoutHours', label: '超时（小时）', type: 'number', placeholder: '24', hint: '0=不超时' },
      { key: 'timeoutAction', label: '超时动作', type: 'select', options: timeoutActionOptions, defaultValue: 'approve' },
    ],
  },
  {
    type: 'approval', label: '审批', category: 'task',
    icon: 'Stamp', color: 'text-rose-400', bgColor: 'bg-rose-500/10', borderColor: 'border-rose-500/30',
    description: '多实例审批：或签/会签/比例（P2）',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [
      { id: 'approved', label: '通过', type: 'output' },
      { id: 'rejected', label: '驳回', type: 'output' },
    ],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '审批', defaultValue: '审批' },
      { key: 'approverIds', label: '审批人 ID 列表', type: 'json', placeholder: '["01M3...","01M4..."]', required: true,
        hint: 'JSON 数组；每位审批人一行独立审批' },
      { key: 'assigneeRole', label: '审批角色', type: 'text', placeholder: 'manager（与审批人二选一）' },
      { key: 'approvalMode', label: '会签策略', type: 'select', options: [
        { value: 'ANY', label: '或签（任一通过）' },
        { value: 'ALL', label: '会签（全部通过）' },
        { value: 'RATIO', label: '比例通过' },
      ], defaultValue: 'ALL' },
      { key: 'approveRatio', label: '通过比例', type: 'number', placeholder: '1.0', hint: 'RATIO 策略生效，0-1' },
      { key: 'timeoutHours', label: '超时（小时）', type: 'number', placeholder: '48', hint: '0=不超时' },
      { key: 'timeoutAction', label: '超时动作', type: 'select', options: timeoutActionOptions, defaultValue: 'reject' },
      { key: 'rejectTargetNodeId', label: '驳回回退目标', type: 'text', placeholder: '节点 ID（重审轮次+1）' },
    ],
  },

  // ===== 逻辑控制（6） =====
  {
    type: 'condition', label: '条件判断', category: 'logic',
    icon: 'GitBranch', color: 'text-purple-400', bgColor: 'bg-purple-500/10', borderColor: 'border-purple-500/30',
    description: '表达式真/假两路分支',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [
      { id: 'true', label: 'True', type: 'output' },
      { id: 'false', label: 'False', type: 'output' },
    ],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '条件判断', defaultValue: '条件判断' },
      { key: 'expression', label: '条件表达式', type: 'textarea', placeholder: 'amount > 1000 && level >= 2',
        required: true, hint: '引擎表达式语法：变量名/点路径/比较逻辑/白名单函数' },
    ],
  },
  {
    type: 'switch', label: '多路分支', category: 'logic',
    icon: 'Split', color: 'text-purple-400', bgColor: 'bg-purple-500/10', borderColor: 'border-purple-500/30',
    description: '按表达式结果值路由到对应出边',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [
      { id: 'case0', label: '分支1', type: 'output' },
      { id: 'case1', label: '分支2', type: 'output' },
      { id: 'default', label: '默认', type: 'output' },
    ],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '多路分支', defaultValue: '多路分支' },
      { key: 'expression', label: '路由表达式', type: 'text', placeholder: 'data.orderType', required: true,
        hint: '输出值与出边标签匹配（label=case 值/default 兜底）——请在连线上设置标签' },
    ],
  },
  {
    type: 'decision', label: '决策表', category: 'logic',
    icon: 'Table2', color: 'text-purple-400', bgColor: 'bg-purple-500/10', borderColor: 'border-purple-500/30',
    description: '条件→输出映射表，命中首行输出（P2）',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '决策表', defaultValue: '决策表' },
      { key: 'decisions', label: '规则行', type: 'json', required: true,
        placeholder: '[{"when": "amount > 10000", "value": "VIP"}, {"when": "amount > 1000", "value": "普通"}]',
        hint: 'JSON 数组，每行 {when 条件表达式, value 输出值}，从上到下首行命中' },
      { key: 'default', label: '兜底输出', type: 'text', placeholder: '普通', defaultValue: '' },
    ],
  },
  {
    type: 'filter', label: '门控过滤', category: 'logic',
    icon: 'Filter', color: 'text-purple-400', bgColor: 'bg-purple-500/10', borderColor: 'border-purple-500/30',
    description: '表达式通过放行 / 不通过拦截',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [
      { id: 'pass', label: '通过', type: 'output' },
      { id: 'block', label: '拦截', type: 'output' },
    ],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '门控过滤', defaultValue: '门控过滤' },
      { key: 'expression', label: '过滤表达式', type: 'textarea', placeholder: 'item.score > 60',
        required: true, hint: 'true 走"通过"边，false 走"拦截"边（拦截边建议接错误终态）' },
    ],
  },
  {
    type: 'loop', label: '循环遍历', category: 'logic',
    icon: 'Repeat', color: 'text-purple-400', bgColor: 'bg-purple-500/10', borderColor: 'border-purple-500/30',
    description: '遍历数组逐个/并行处理',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [
      { id: 'item', label: '循环体', type: 'output' },
      { id: 'done', label: '完成', type: 'output' },
    ],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '循环遍历', defaultValue: '循环遍历' },
      { key: 'arrayExpr', label: '数组表达式', type: 'text', placeholder: 'data.items', required: true,
        hint: '求值为数组的表达式' },
      { key: 'maxIterations', label: '最大迭代次数', type: 'number', placeholder: '100', defaultValue: '100' },
    ],
  },
  {
    type: 'parallel', label: '并行网关', category: 'logic',
    icon: 'GitFork', color: 'text-purple-400', bgColor: 'bg-purple-500/10', borderColor: 'border-purple-500/30',
    description: '扇出到多条并行分支',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [
      { id: 'b0', label: '分支1', type: 'output' },
      { id: 'b1', label: '分支2', type: 'output' },
    ],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '并行网关', defaultValue: '并行网关' },
      { key: 'hint', label: '分支数', type: 'text', placeholder: '由出边数量决定',
        hint: '每条出边一条并行分支，汇聚用"汇聚网关"节点' },
    ],
  },
  {
    type: 'join', label: '汇聚网关', category: 'logic',
    icon: 'Merge', color: 'text-purple-400', bgColor: 'bg-purple-500/10', borderColor: 'border-purple-500/30',
    description: '等待多分支到达后收口（P1）',
    inputs: [
      { id: 'in0', label: '输入1', type: 'input' },
      { id: 'in1', label: '输入2', type: 'input' },
    ],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '汇聚网关', defaultValue: '汇聚网关' },
      { key: 'joinStrategy', label: '汇聚策略', type: 'select', options: [
        { value: 'ALL', label: '等待全部分支（ALL）' },
        { value: 'FIRST', label: '首个到达即放行（FIRST）' },
        { value: 'N_OF_M', label: '到达 N 个放行（N_OF_M）' },
      ], defaultValue: 'ALL' },
      { key: 'joinCount', label: '到达数量 N', type: 'number', placeholder: '2', hint: 'N_OF_M 策略生效' },
    ],
  },

  // ===== AI 能力（4） =====
  {
    type: 'llm', label: '大模型调用', category: 'ai',
    icon: 'Bot', color: 'text-tech-400', bgColor: 'bg-tech-500/10', borderColor: 'border-tech-500/30',
    description: '直调平台 LLM 客户端栈（P4）',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '大模型调用', defaultValue: '大模型调用' },
      { key: 'modelProvider', label: '模型提供商', type: 'text', placeholder: 'zhipu', required: true,
        hint: '平台 model_provider 表中启用的 provider 编码（如 zhipu）' },
      { key: 'modelName', label: '模型名', type: 'text', placeholder: 'glm-4（留空用提供商默认）' },
      { key: 'promptTemplate', label: '提示词模板', type: 'textarea', placeholder: '总结以下内容：${content}',
        required: true, hint: '支持 ${变量名} 占位符引用流程变量与上游输出' },
      { key: 'temperature', label: '温度', type: 'number', placeholder: '0.7' },
      { key: 'maxTokens', label: '最大 Token', type: 'number', placeholder: '2048' },
    ],
  },
  {
    type: 'agent', label: '智能体', category: 'ai',
    icon: 'BrainCircuit', color: 'text-tech-400', bgColor: 'bg-tech-500/10', borderColor: 'border-tech-500/30',
    description: '调用 ReAct 智能体执行任务（P4）',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '智能体', defaultValue: '智能体' },
      { key: 'agentId', label: '智能体 ID', type: 'text', placeholder: '01M3...', required: true },
      { key: 'taskTemplate', label: '任务模板', type: 'textarea', placeholder: '分析 ${topic} 并给出结论',
        required: true, hint: '支持 ${变量名} 占位符；会话 ID 填工作流实例 ID 可追溯' },
    ],
  },
  {
    type: 'orchestration', label: '编排图', category: 'ai',
    icon: 'Workflow', color: 'text-tech-400', bgColor: 'bg-tech-500/10', borderColor: 'border-tech-500/30',
    description: '同步执行已激活编排图（P4，WORKFLOW_CALL）',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '编排图调用', defaultValue: '编排图调用' },
      { key: 'graphId', label: '编排图 ID', type: 'text', placeholder: '01M3...（须为 active）', required: true },
      { key: 'inputTemplate', label: '输入模板', type: 'textarea', placeholder: '处理 ${input}',
        hint: '渲染结果作为图 input 变量；编排执行记录 sessionId 填本实例 ID' },
    ],
  },
  {
    type: 'knowledge', label: '知识检索', category: 'ai',
    icon: 'Search', color: 'text-tech-400', bgColor: 'bg-tech-500/10', borderColor: 'border-tech-500/30',
    description: '经平台检索源获取知识片段（P4）',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '知识检索', defaultValue: '知识检索' },
      { key: 'knowledgeBaseId', label: '知识库标识', type: 'text', placeholder: 'kb-default', required: true },
      { key: 'queryTemplate', label: '查询模板', type: 'textarea', placeholder: '${topic} 最新进展',
        required: true, hint: '支持 ${变量名} 占位符' },
      { key: 'topK', label: '返回条数', type: 'number', placeholder: '5', defaultValue: '5' },
    ],
  },

  // ===== 数据处理（3） =====
  {
    type: 'transform', label: '数据转换', category: 'data',
    icon: 'ArrowRightLeft', color: 'text-cyan-400', bgColor: 'bg-cyan-500/10', borderColor: 'border-cyan-500/30',
    description: '按映射表重组数据结构',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '数据转换', defaultValue: '数据转换' },
      { key: 'mapping', label: '字段映射', type: 'json', placeholder: '{"total": "amount * count", "name": "data.name"}',
        required: true, hint: 'JSON 对象：目标字段 ← 表达式' },
    ],
  },
  {
    type: 'json-parse', label: 'JSON 解析', category: 'data',
    icon: 'Braces', color: 'text-cyan-400', bgColor: 'bg-cyan-500/10', borderColor: 'border-cyan-500/30',
    description: '解析 JSON 字符串字段',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: 'JSON 解析', defaultValue: 'JSON 解析' },
      { key: 'field', label: '待解析字段路径', type: 'text', placeholder: 'data.jsonString', required: true },
    ],
  },
  {
    type: 'set-variable', label: '变量赋值', category: 'data',
    icon: 'Variable', color: 'text-cyan-400', bgColor: 'bg-cyan-500/10', borderColor: 'border-cyan-500/30',
    description: '求值表达式写入流程变量空间',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '变量赋值', defaultValue: '变量赋值' },
      { key: 'variables', label: '变量集', type: 'json', placeholder: '{"status": "approved", "count": "data.items.length"}',
        required: true, hint: 'JSON 对象：变量名 ← 表达式' },
    ],
  },

  // ===== 集成对接（1） =====
  {
    type: 'http-request', label: 'HTTP 请求', category: 'integration',
    icon: 'Globe', color: 'text-blue-400', bgColor: 'bg-blue-500/10', borderColor: 'border-blue-500/30',
    description: '调用外部 HTTP 服务（SSRF 防护+重试）',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: 'HTTP 请求', defaultValue: 'HTTP 请求' },
      { key: 'method', label: '请求方法', type: 'select', options: [{ value: 'GET', label: 'GET' }, { value: 'POST', label: 'POST' }, { value: 'PUT', label: 'PUT' }, { value: 'DELETE', label: 'DELETE' }], defaultValue: 'GET' },
      { key: 'url', label: '请求地址', type: 'text', placeholder: 'https://api.example.com/data', required: true,
        hint: '支持 ${变量名} 占位符；内网地址默认拒绝' },
      { key: 'body', label: '请求体模板', type: 'textarea', placeholder: '{"order": "${orderId}"}',
        hint: '支持 ${变量名} 占位符（POST/PUT）' },
      { key: 'timeoutSeconds', label: '超时（秒）', type: 'number', placeholder: '30', defaultValue: '30' },
      { key: 'retryCount', label: '重试次数', type: 'number', placeholder: '2', defaultValue: '2' },
      { key: 'retryBackoffMs', label: '重试退避（毫秒）', type: 'number', placeholder: '1000', defaultValue: '1000' },
    ],
  },

  // ===== 流程控制与事件（6） =====
  {
    type: 'delay', label: '延时等待', category: 'flow',
    icon: 'Timer', color: 'text-orange-400', bgColor: 'bg-orange-500/10', borderColor: 'border-orange-500/30',
    description: '等待指定时长后继续',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '延时等待', defaultValue: '延时等待' },
      { key: 'duration', label: '等待时长', type: 'number', placeholder: '30', required: true },
      { key: 'unit', label: '时间单位', type: 'select', options: [{ value: 'SECONDS', label: '秒' }, { value: 'MINUTES', label: '分钟' }, { value: 'HOURS', label: '小时' }], defaultValue: 'SECONDS' },
    ],
  },
  {
    type: 'receive-message', label: '消息等待', category: 'flow',
    icon: 'Inbox', color: 'text-orange-400', bgColor: 'bg-orange-500/10', borderColor: 'border-orange-500/30',
    description: '挂起至外部消息交付（P3）',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '消息等待', defaultValue: '消息等待' },
      { key: 'messageKey', label: '消息关联键', type: 'text', placeholder: 'order-001', required: true,
        hint: '交付端点携此键推进（实例内/跨实例均可）' },
      { key: 'timeoutHours', label: '超时（小时）', type: 'number', placeholder: '72', hint: '0=不超时' },
      { key: 'timeoutAction', label: '超时动作', type: 'select', options: timeoutActionOptions, defaultValue: 'approve' },
    ],
  },
  {
    type: 'respond', label: '同步响应', category: 'flow',
    icon: 'Reply', color: 'text-orange-400', bgColor: 'bg-orange-500/10', borderColor: 'border-orange-500/30',
    description: '向 Webhook 调用方同步返回结果（P3）',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '同步响应', defaultValue: '同步响应' },
      { key: 'payload', label: '响应载荷', type: 'json', placeholder: '{"code": 0, "msg": "已受理"}',
        hint: 'JSON；留空则透传上游输出。与 webhook-trigger 配对，等待上限 15s' },
    ],
  },
  {
    type: 'event-wait', label: '事件等待', category: 'flow',
    icon: 'Radio', color: 'text-orange-400', bgColor: 'bg-orange-500/10', borderColor: 'border-orange-500/30',
    description: '挂起至订阅事件之一到达（P3）',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '事件等待', defaultValue: '事件等待' },
      { key: 'eventTypes', label: '订阅事件类型', type: 'json', required: true,
        placeholder: '["evt.paid", "evt.cancelled"]',
        hint: 'JSON 数组；任一事件到达即唤醒（Event Gateway 对位）' },
      { key: 'timeoutHours', label: '超时（小时）', type: 'number', placeholder: '72', hint: '0=不超时' },
      { key: 'timeoutAction', label: '超时动作', type: 'select', options: timeoutActionOptions, defaultValue: 'approve' },
    ],
  },

  // ===== 终结事件（3） =====
  {
    type: 'sub-workflow', label: '子工作流', category: 'flow',
    icon: 'Workflow', color: 'text-orange-400', bgColor: 'bg-orange-500/10', borderColor: 'border-orange-500/30',
    description: '启动子工作流并等待其完成（53 号 §3.7）',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '子工作流', defaultValue: '子工作流' },
      { key: 'workflowId', label: '子工作流 ID', type: 'text', placeholder: '01M3...（须为已发布）', required: true,
        hint: '子流程成功完成以其最终输出推进本节点；失败/终止联动本实例失败。嵌套上限 10 层' },
      { key: 'inputTemplate', label: '输入模板', type: 'textarea', placeholder: '{"order": "${orderId}"}',
        hint: '支持 ${变量名} 占位符；子流程变量继承父空间' },
    ],
  },
  {
    type: 'return', label: '返回结果', category: 'terminal',
    icon: 'CornerDownRight', color: 'text-red-400', bgColor: 'bg-red-500/10', borderColor: 'border-red-500/30',
    description: '正常终态：实例完成并输出结果',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '返回结果', defaultValue: '返回结果' },
      { key: 'outputExpression', label: '输出表达式', type: 'textarea', placeholder: '{"result": data}',
        hint: '留空则透传上游输出' },
    ],
  },
  {
    type: 'error-end', label: '错误终态', category: 'terminal',
    icon: 'OctagonX', color: 'text-red-400', bgColor: 'bg-red-500/10', borderColor: 'border-red-500/30',
    description: '实例置为失败（错误处理落点）',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '错误终态', defaultValue: '错误终态' },
      { key: 'message', label: '失败原因', type: 'text', placeholder: '流程在错误终态结束' },
    ],
  },
  {
    type: 'terminate-end', label: '终止终态', category: 'terminal',
    icon: 'Ban', color: 'text-red-400', bgColor: 'bg-red-500/10', borderColor: 'border-red-500/30',
    description: '取消全部在途分支并终止实例',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '终止终态', defaultValue: '终止终态' },
    ],
  },
];

// --- Helpers ---

export const generateId = () => `node_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;

export function getNodeConfig(type: NodeType): NodeTypeConfig {
  return nodeTypes.find(t => t.type === type)!;
}

export function getDefaultConfig(type: NodeType): Record<string, string> {
  const config = getNodeConfig(type);
  const result: Record<string, string> = {};
  config.configFields.forEach(f => {
    if (f.defaultValue) result[f.key] = f.defaultValue;
  });
  return result;
}

/**
 * 保存前同源校验（对齐后端 WV-06 必填项）：
 * 返回缺失必填项的节点标签列表（空数组=通过）。name 字段不参与校验。
 */
export function validateRequiredConfig(nodes: WorkflowNode[]): string[] {
  const missing: string[] = [];
  for (const node of nodes) {
    const cfg = nodeTypes.find(t => t.type === node.type);
    if (!cfg) continue;
    for (const field of cfg.configFields) {
      if (!field.required || field.key === 'name') continue;
      const value = node.config[field.key];
      if (value == null || String(value).trim() === '') {
        missing.push(`${node.label}（${cfg.label}）缺少 ${field.label}`);
      }
    }
  }
  return missing;
}

/**
 * 构建 config JSON 文本（保存链路用）：
 * json 类型字段解析后原样嵌入（decisions/eventTypes/approverIds 等以结构传给后端），
 * 其余字段以字符串传；name 字段映射为节点名不重复入 config。
 */
export function buildNodeConfigJson(node: WorkflowNode): string {
  const cfg = nodeTypes.find(t => t.type === node.type);
  const out: Record<string, unknown> = {};
  for (const field of cfg?.configFields ?? []) {
    if (field.key === 'name') continue;
    const raw = node.config[field.key];
    if (raw == null || String(raw).trim() === '') continue;
    if (field.type === 'json') {
      try {
        out[field.key] = JSON.parse(String(raw));
      } catch {
        out[field.key] = String(raw); // 交由后端校验报错
      }
    } else if (field.type === 'number') {
      const num = Number(raw);
      out[field.key] = Number.isFinite(num) ? num : String(raw);
    } else {
      out[field.key] = String(raw);
    }
  }
  return JSON.stringify(out);
}
