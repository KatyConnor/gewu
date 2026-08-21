// 工作流画布 - 类型定义与节点配置

export type NodeType =
  | 'manual-trigger' | 'schedule-trigger' | 'webhook-trigger' | 'event-trigger'
  | 'condition' | 'switch' | 'loop' | 'filter'
  | 'llm' | 'rag' | 'agent' | 'code'
  | 'transform' | 'json-parse' | 'set-variable'
  | 'http-request' | 'database' | 'email' | 'notification'
  | 'delay' | 'counter' | 'sub-workflow' | 'return';

export type NodeCategory = 'trigger' | 'logic' | 'ai' | 'data' | 'integration' | 'flow';

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
  type: 'text' | 'textarea' | 'select' | 'number';
  placeholder?: string;
  options?: { value: string; label: string }[];
  defaultValue?: string;
  hint?: string;
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
  logic:       { label: '逻辑控制', color: 'text-purple-400', bgColor: 'bg-purple-500/10' },
  ai:          { label: 'AI 能力', color: 'text-tech-400', bgColor: 'bg-tech-500/10' },
  data:        { label: '数据处理', color: 'text-cyan-400', bgColor: 'bg-cyan-500/10' },
  integration: { label: '集成对接', color: 'text-blue-400', bgColor: 'bg-blue-500/10' },
  flow:        { label: '流程控制', color: 'text-orange-400', bgColor: 'bg-orange-500/10' },
};

// --- Node Type Definitions ---

export const nodeTypes: NodeTypeConfig[] = [
  // ===== 触发器 =====
  {
    type: 'manual-trigger', label: '手动触发', category: 'trigger',
    icon: 'Hand', color: 'text-amber-400', bgColor: 'bg-amber-500/10', borderColor: 'border-amber-500/30',
    description: '手动启动工作流',
    inputs: [],
    outputs: [{ id: 'out', label: '开始', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '手动触发', defaultValue: '手动触发' },
    ],
  },
  {
    type: 'schedule-trigger', label: '定时触发', category: 'trigger',
    icon: 'Clock', color: 'text-amber-400', bgColor: 'bg-amber-500/10', borderColor: 'border-amber-500/30',
    description: '按时间计划自动触发',
    inputs: [],
    outputs: [{ id: 'out', label: '开始', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '定时触发', defaultValue: '定时触发' },
      { key: 'cron', label: 'Cron 表达式', type: 'text', placeholder: '0 9 * * 1-5', hint: '分 时 日 月 周', defaultValue: '0 9 * * 1-5' },
      { key: 'timezone', label: '时区', type: 'text', placeholder: 'Asia/Shanghai', defaultValue: 'Asia/Shanghai' },
    ],
  },
  {
    type: 'webhook-trigger', label: 'Webhook', category: 'trigger',
    icon: 'Globe', color: 'text-amber-400', bgColor: 'bg-amber-500/10', borderColor: 'border-amber-500/30',
    description: '接收外部 HTTP 请求触发',
    inputs: [],
    outputs: [{ id: 'out', label: '开始', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: 'Webhook', defaultValue: 'Webhook' },
      { key: 'method', label: '请求方法', type: 'select', options: [{ value: 'GET', label: 'GET' }, { value: 'POST', label: 'POST' }, { value: 'PUT', label: 'PUT' }], defaultValue: 'POST' },
      { key: 'path', label: '路径', type: 'text', placeholder: '/webhook/my-flow', defaultValue: '/webhook/my-flow' },
    ],
  },
  {
    type: 'event-trigger', label: '事件触发', category: 'trigger',
    icon: 'Zap', color: 'text-amber-400', bgColor: 'bg-amber-500/10', borderColor: 'border-amber-500/30',
    description: '监听系统事件触发',
    inputs: [],
    outputs: [{ id: 'out', label: '开始', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '事件触发', defaultValue: '事件触发' },
      { key: 'eventType', label: '事件类型', type: 'select', options: [{ value: 'file_created', label: '文件创建' }, { value: 'data_updated', label: '数据更新' }, { value: 'user_action', label: '用户操作' }], defaultValue: 'file_created' },
    ],
  },

  // ===== 逻辑控制 =====
  {
    type: 'condition', label: '条件判断', category: 'logic',
    icon: 'GitBranch', color: 'text-purple-400', bgColor: 'bg-purple-500/10', borderColor: 'border-purple-500/30',
    description: 'If/Else 条件分支',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [
      { id: 'true', label: 'True', type: 'output' },
      { id: 'false', label: 'False', type: 'output' },
    ],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '条件判断', defaultValue: '条件判断' },
      { key: 'field', label: '判断字段', type: 'text', placeholder: 'data.status', hint: '使用点号访问嵌套字段' },
      { key: 'operator', label: '操作符', type: 'select', options: [
        { value: 'equals', label: '等于 (==)' }, { value: 'not_equals', label: '不等于 (!=)' },
        { value: 'gt', label: '大于 (>)' }, { value: 'gte', label: '大于等于 (>=)' },
        { value: 'lt', label: '小于 (<)' }, { value: 'lte', label: '小于等于 (<=)' },
        { value: 'contains', label: '包含' }, { value: 'empty', label: '为空' }, { value: 'not_empty', label: '非空' },
      ], defaultValue: 'equals' },
      { key: 'value', label: '比较值', type: 'text', placeholder: '200' },
    ],
  },
  {
    type: 'switch', label: '多路分支', category: 'logic',
    icon: 'Split', color: 'text-purple-400', bgColor: 'bg-purple-500/10', borderColor: 'border-purple-500/30',
    description: '根据值匹配走不同分支',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [
      { id: 'case0', label: '分支1', type: 'output' },
      { id: 'case1', label: '分支2', type: 'output' },
      { id: 'default', label: '默认', type: 'output' },
    ],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '多路分支', defaultValue: '多路分支' },
      { key: 'field', label: '路由字段', type: 'text', placeholder: 'data.type' },
      { key: 'cases', label: '分支值（每行一个）', type: 'textarea', placeholder: 'type_a\ntype_b\ntype_c', hint: '每行一个匹配值' },
    ],
  },
  {
    type: 'loop', label: '循环遍历', category: 'logic',
    icon: 'Repeat', color: 'text-purple-400', bgColor: 'bg-purple-500/10', borderColor: 'border-purple-500/30',
    description: '遍历数组逐个处理',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [
      { id: 'item', label: '循环体', type: 'output' },
      { id: 'done', label: '完成', type: 'output' },
    ],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '循环遍历', defaultValue: '循环遍历' },
      { key: 'arrayField', label: '数组字段', type: 'text', placeholder: 'data.items', hint: '要遍历的数组字段路径' },
      { key: 'maxIterations', label: '最大迭代次数', type: 'number', placeholder: '100', defaultValue: '100' },
    ],
  },
  {
    type: 'filter', label: '门控过滤', category: 'logic',
    icon: 'Filter', color: 'text-purple-400', bgColor: 'bg-purple-500/10', borderColor: 'border-purple-500/30',
    description: '过滤不符合条件的数据',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [
      { id: 'pass', label: '通过', type: 'output' },
      { id: 'block', label: '拦截', type: 'output' },
    ],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '门控过滤', defaultValue: '门控过滤' },
      { key: 'expression', label: '过滤表达式', type: 'textarea', placeholder: 'item.score > 60', hint: '使用 item 引用当前元素' },
    ],
  },

  // ===== AI 能力 =====
  {
    type: 'llm', label: '大模型调用', category: 'ai',
    icon: 'Bot', color: 'text-tech-400', bgColor: 'bg-tech-500/10', borderColor: 'border-tech-500/30',
    description: '调用 LLM 生成内容',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '大模型调用', defaultValue: '大模型调用' },
      { key: 'model', label: '模型', type: 'select', options: [
        { value: 'gpt-4o', label: 'GPT-4o' }, { value: 'gpt-4o-mini', label: 'GPT-4o Mini' },
        { value: 'claude-3.5-sonnet', label: 'Claude 3.5 Sonnet' }, { value: 'deepseek-v3', label: 'DeepSeek V3' },
        { value: 'qwen-max', label: 'Qwen Max' },
      ], defaultValue: 'gpt-4o' },
      { key: 'prompt', label: '系统提示词', type: 'textarea', placeholder: '你是一个专业的...', hint: '定义 AI 角色和任务' },
      { key: 'temperature', label: '温度', type: 'number', placeholder: '0.7', hint: '0-2，越高越有创意', defaultValue: '0.7' },
      { key: 'maxTokens', label: '最大Token', type: 'number', placeholder: '2048', defaultValue: '2048' },
    ],
  },
  {
    type: 'rag', label: '知识检索', category: 'ai',
    icon: 'Search', color: 'text-tech-400', bgColor: 'bg-tech-500/10', borderColor: 'border-tech-500/30',
    description: '从知识库检索相关内容',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '知识检索', defaultValue: '知识检索' },
      { key: 'knowledgeBase', label: '知识库', type: 'select', options: [{ value: 'kb_1', label: '产品文档库' }, { value: 'kb_2', label: '技术知识库' }, { value: 'kb_3', label: 'FAQ知识库' }], defaultValue: 'kb_1' },
      { key: 'topK', label: '检索数量', type: 'number', placeholder: '5', defaultValue: '5' },
      { key: 'threshold', label: '相似度阈值', type: 'number', placeholder: '0.7', defaultValue: '0.7' },
    ],
  },
  {
    type: 'agent', label: '智能体', category: 'ai',
    icon: 'Bot', color: 'text-tech-400', bgColor: 'bg-tech-500/10', borderColor: 'border-tech-500/30',
    description: '自主决策执行的 AI Agent',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '智能体', defaultValue: '智能体' },
      { key: 'agentId', label: '智能体', type: 'select', options: [{ value: 'agent_1', label: '代码专家' }, { value: 'agent_2', label: '数据分析师' }, { value: 'agent_3', label: '文档助手' }], defaultValue: 'agent_1' },
      { key: 'task', label: '任务描述', type: 'textarea', placeholder: '请分析数据并生成报告...' },
    ],
  },
  {
    type: 'code', label: '代码执行', category: 'ai',
    icon: 'Code', color: 'text-tech-400', bgColor: 'bg-tech-500/10', borderColor: 'border-tech-500/30',
    description: '执行自定义代码逻辑',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '代码执行', defaultValue: '代码执行' },
      { key: 'language', label: '语言', type: 'select', options: [{ value: 'python', label: 'Python' }, { value: 'javascript', label: 'JavaScript' }], defaultValue: 'python' },
      { key: 'code', label: '代码', type: 'textarea', placeholder: 'def process(data):\n    return {"result": data}', hint: '定义 process(data) 函数' },
    ],
  },

  // ===== 数据处理 =====
  {
    type: 'transform', label: '数据转换', category: 'data',
    icon: 'ArrowRightLeft', color: 'text-cyan-400', bgColor: 'bg-cyan-500/10', borderColor: 'border-cyan-500/30',
    description: '转换数据结构和格式',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '数据转换', defaultValue: '数据转换' },
      { key: 'mapping', label: '字段映射（JSON）', type: 'textarea', placeholder: '{"newField": "oldField"}', hint: '定义字段映射关系' },
    ],
  },
  {
    type: 'json-parse', label: 'JSON 解析', category: 'data',
    icon: 'Braces', color: 'text-cyan-400', bgColor: 'bg-cyan-500/10', borderColor: 'border-cyan-500/30',
    description: '解析 JSON 字符串为对象',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: 'JSON 解析', defaultValue: 'JSON 解析' },
      { key: 'field', label: '待解析字段', type: 'text', placeholder: 'data.jsonString' },
    ],
  },
  {
    type: 'set-variable', label: '变量赋值', category: 'data',
    icon: 'Variable', color: 'text-cyan-400', bgColor: 'bg-cyan-500/10', borderColor: 'border-cyan-500/30',
    description: '设置或修改变量值',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '变量赋值', defaultValue: '变量赋值' },
      { key: 'variables', label: '变量（每行 key=value）', type: 'textarea', placeholder: 'status=approved\ncount=10' },
    ],
  },

  // ===== 集成对接 =====
  {
    type: 'http-request', label: 'HTTP 请求', category: 'integration',
    icon: 'Globe', color: 'text-blue-400', bgColor: 'bg-blue-500/10', borderColor: 'border-blue-500/30',
    description: '发送 HTTP 请求到外部服务',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: 'HTTP 请求', defaultValue: 'HTTP 请求' },
      { key: 'method', label: '请求方法', type: 'select', options: [{ value: 'GET', label: 'GET' }, { value: 'POST', label: 'POST' }, { value: 'PUT', label: 'PUT' }, { value: 'DELETE', label: 'DELETE' }], defaultValue: 'GET' },
      { key: 'url', label: '请求地址', type: 'text', placeholder: 'https://api.example.com/data' },
      { key: 'headers', label: '请求头（JSON）', type: 'textarea', placeholder: '{"Content-Type": "application/json"}' },
      { key: 'body', label: '请求体', type: 'textarea', placeholder: '{"key": "value"}', hint: 'POST/PUT 时填写' },
    ],
  },
  {
    type: 'database', label: '数据库操作', category: 'integration',
    icon: 'Database', color: 'text-blue-400', bgColor: 'bg-blue-500/10', borderColor: 'border-blue-500/30',
    description: '查询或修改数据库',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '数据库操作', defaultValue: '数据库操作' },
      { key: 'operation', label: '操作类型', type: 'select', options: [{ value: 'select', label: '查询 SELECT' }, { value: 'insert', label: '插入 INSERT' }, { value: 'update', label: '更新 UPDATE' }, { value: 'delete', label: '删除 DELETE' }], defaultValue: 'select' },
      { key: 'table', label: '表名', type: 'text', placeholder: 'users' },
      { key: 'query', label: 'SQL / 条件', type: 'textarea', placeholder: 'SELECT * FROM users WHERE id = ?' },
    ],
  },
  {
    type: 'email', label: '发送邮件', category: 'integration',
    icon: 'Mail', color: 'text-blue-400', bgColor: 'bg-blue-500/10', borderColor: 'border-blue-500/30',
    description: '发送电子邮件通知',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '发送邮件', defaultValue: '发送邮件' },
      { key: 'to', label: '收件人', type: 'text', placeholder: 'user@example.com' },
      { key: 'subject', label: '主题', type: 'text', placeholder: '工作流通知' },
      { key: 'content', label: '正文', type: 'textarea', placeholder: '您好，工作流已完成...' },
    ],
  },
  {
    type: 'notification', label: '通知推送', category: 'integration',
    icon: 'Bell', color: 'text-blue-400', bgColor: 'bg-blue-500/10', borderColor: 'border-blue-500/30',
    description: '推送消息到 IM 平台',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '通知推送', defaultValue: '通知推送' },
      { key: 'channel', label: '推送渠道', type: 'select', options: [{ value: 'dingtalk', label: '钉钉' }, { value: 'wecom', label: '企业微信' }, { value: 'feishu', label: '飞书' }, { value: 'slack', label: 'Slack' }], defaultValue: 'dingtalk' },
      { key: 'message', label: '消息内容', type: 'textarea', placeholder: '工作流 {{workflowName}} 已完成' },
    ],
  },

  // ===== 流程控制 =====
  {
    type: 'delay', label: '延时等待', category: 'flow',
    icon: 'Timer', color: 'text-orange-400', bgColor: 'bg-orange-500/10', borderColor: 'border-orange-500/30',
    description: '暂停等待指定时间',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '延时等待', defaultValue: '延时等待' },
      { key: 'duration', label: '等待时长', type: 'number', placeholder: '30', defaultValue: '30' },
      { key: 'unit', label: '时间单位', type: 'select', options: [{ value: 'seconds', label: '秒' }, { value: 'minutes', label: '分钟' }, { value: 'hours', label: '小时' }], defaultValue: 'seconds' },
    ],
  },
  {
    type: 'counter', label: '计数器', category: 'flow',
    icon: 'Hash', color: 'text-orange-400', bgColor: 'bg-orange-500/10', borderColor: 'border-orange-500/30',
    description: '统计通过的数据条数',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '计数器', defaultValue: '计数器' },
      { key: 'resetOn', label: '重置条件', type: 'select', options: [{ value: 'never', label: '不重置' }, { value: 'daily', label: '每日重置' }, { value: 'workflow', label: '每次运行重置' }], defaultValue: 'workflow' },
    ],
  },
  {
    type: 'sub-workflow', label: '子工作流', category: 'flow',
    icon: 'Workflow', color: 'text-orange-400', bgColor: 'bg-orange-500/10', borderColor: 'border-orange-500/30',
    description: '调用另一个工作流',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [{ id: 'out', label: '输出', type: 'output' }],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '子工作流', defaultValue: '子工作流' },
      { key: 'workflowId', label: '子工作流', type: 'select', options: [{ value: 'wf_1', label: '数据预处理流程' }, { value: 'wf_2', label: '审批流程' }], defaultValue: 'wf_1' },
    ],
  },
  {
    type: 'return', label: '返回结果', category: 'flow',
    icon: 'CornerDownRight', color: 'text-orange-400', bgColor: 'bg-orange-500/10', borderColor: 'border-orange-500/30',
    description: '结束并返回结果',
    inputs: [{ id: 'in', label: '输入', type: 'input' }],
    outputs: [],
    configFields: [
      { key: 'name', label: '节点名称', type: 'text', placeholder: '返回结果', defaultValue: '返回结果' },
      { key: 'outputExpression', label: '输出表达式', type: 'textarea', placeholder: '{"result": data}', hint: '定义返回的数据结构' },
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
