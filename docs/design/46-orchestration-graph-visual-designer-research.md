# 编排图可视化设计器 · 功能需求调研分析报告

> 编号：46-orchestration-graph-visual-designer-research
> 版本：V1.0 · 2026-09-24
> 性质：调研分析报告（供后续优化设计参考，非实施方案定稿）
> 调研对象：编排引擎（orchestration）"创建编排图"功能及其可视化设计器可行性
> 关联文档：29-agent-orchestration-engine.md、28-workflow-engine-design.md（V1.2 边界澄清）、docs/workflow-canvas-manual.md

---

## 一、背景与问题定义

当前创建编排图的唯一入口是"手写 JSON 文本框"（`gewu-web/src/components/pages/OrchestrationPage.tsx:397-402`）：使用者必须记住 `OrchestrationGraph / GraphNode / GraphEdge` 全部属性名与含义，才能写出一份可执行的图定义。这对非开发角色（运维、业务配置员）形成了实质门槛，对开发角色也是低效且易错的。

调研确认了当前功能已存在的 5 个可用性/正确性缺陷：

| # | 缺陷 | 证据 |
|---|------|------|
| D1 | **placeholder 键名错误**：示例 `{"nodes":[{"id":"n1","name":"步骤一"}],"edges":[]}` 使用的 `id`/`name` 与后端 schema（`nodeId` 等）不匹配，未知键被 Jackson 静默忽略，按示例填写将得到 `nodeId=null` 的不可执行图 | `OrchestrationPage.tsx:397` vs `GraphNode.java:19-35` |
| D2 | **"可选"标注与后端校验矛盾**：前端标注"图定义 JSON（可选）"，后端 `createGraph` 对空定义直接抛"编排图定义不能为空" | `OrchestrationService.java:70-72` |
| D3 | **下拉框选择的编排模式不影响执行**：弹窗所选 mode 只存入 `orchestration_graph.orchestration_mode` 列做展示；执行时 `deserializeGraph()` 只解析 JSON、从不把实体上的模式列注入解析结果，引擎按 JSON 内 `mode` 字段分派（缺省 PIPELINE）——不把 mode 写进 JSON，选 SWARM/SUPERVISOR 也按 PIPELINE 跑 | `OrchestrationService.java:528-541`、`Orchestrator.java:109` |
| D4 | **无编辑能力**：前端无图详情/编辑页；后端无更新端点（无 `PUT /graphs/{id}`），创建后无法补填或修改定义，只能删除重建 | `OrchestrationController.java` 全端点清单 |
| D5 | **全链路零校验**：创建仅查非空；JSON 解析推迟到执行时（仅语法/枚举错误暴露）；无图结构 lint（悬空边、环、缺条件、缺起始节点），未知字段被静默忽略 | `OrchestrationService.java`，全库无 GraphValidator |

**结论**：问题不只是"输入方式不友好"，而是"图定义"这条链路缺少产品化的编辑、校验与生效语义。可视化设计器应与这 5 项缺陷的修复打包推进。

---

## 二、调研范围与方法

- **范围**：
  - 前端 `gewu-web`：技术栈、组件体系、主题、路由、请求层、现有画布实现（WorkflowCanvas）；
  - 后端 `gewu-agent-engine` / `gewu-application` / `gewu-interface`：编排图模型、CRUD 端点、属性面板可引用的数据源（Agent/角色/工具/审批/模型）、校验设施、执行事件流；
  - 设计文档：28/29/31 号设计文档、`docs/workflow-canvas-manual.md`、`docs/agent-engine/07-orchestration.md`。
- **方法**：双路并行代码勘察（前端、后端各一路 Explore 子代理）+ 关键结论定点复核（执行链 mode 注入缺失、Controller 端点清单等均为直接读码二次确认）。
- **引用规范**：文中 file:line 均为调研时点（2026-09-24 分支 fix/sandbox-security-storage）的相对路径与行号。

---

## 三、现状盘点

### 3.1 前端现状

**技术栈**：Next.js 14.2.35（App Router 目录，但实际为 `app/page.tsx` 中 `PageType → 组件` 的单页映射，无 URL 路由）+ React 18 + TypeScript 5 + Tailwind 3.4 + Redux Toolkit（仅导航/主题/登录态，业务页面全部局部 useState）+ axios 封装（`lib/request.ts`，baseURL `/api`，next rewrites 代理到 8081）。**未引入任何图/画布库**；dnd-kit 已安装但仅用于需求看板列表排序；Monaco Editor 已自托管于 `public/monaco/vs`（免 CDN 先例），但全项目未启用 JSON Schema 诊断。

**组件体系**：无通用 Dialog/Input/Tabs/Tooltip 封装——弹窗是页面内 `fixed inset-0 z-50` 遮罩 + `glass-dark` 卡片的惯例写法；手写 `CustomSelect`（`ui/Select.tsx`，暗色硬编码 `#0e1c1b`）、Toast（Context 方式）。主题为 4 套 CSS 变量（`lib/themes.ts`：ink/deepsea/jade/celadon；截图中深绿暗色即 ink 主题，主色 `tech-500 #00b894`），`ThemeSync` 把当前主题色阶覆写到 `--tw-*`。**新画布组件必须走 CSS 变量，避免 WorkflowCanvas 的主题硬编码教训。**

**关键资产——已有工作流画布（WorkflowCanvas）**：

| 资产 | 规模 | 评估 |
|---|---|---|
| `WorkflowCanvas.tsx` | 658 行 | 纯 SVG + 绝对定位 div 自绘：贝塞尔连线、端口连接校验、节点拖拽、右键菜单、272px 属性抽屉（按声明式 `ConfigField`：text/textarea/select/number 动态渲染）。**demo 级**：画布内容不持久化（useState 初始化 1 个触发节点）、保存/撤销/重做为假按钮、无运行态着色、无滚轮缩放（仅工具栏 ±0.1）/框选/自动布局/minimap、主题色硬编码 rgba(0,184,148,…) |
| `workflowTypes.ts` | 399 行 | 23 种节点类型目录 + `ConfigField` 声明式表单 schema + 分类配色 + 默认配置。**最干净、可整体复用的部分**——设计器的节点目录/表单 schema 可沿用此模式 |
| `docs/workflow-canvas-manual.md` | 508 行 | 面向最终用户的操作手册，明言参考 n8n/Node-RED/Dify/Coze，固化了"左节点库/上工具栏/中画布/右属性面板"四区交互范式 |

**结论**：UX 范式与节点目录思想可复用；绘制层不建议深挖复用（自绘方案补齐持久化/撤销/运行态/性能的成本高，天花板低）。

### 3.2 后端现状（面向设计器的能力缺口）

**图定义存储与解析**：定义以 JSON 原文存 `orchestration_graph.graph_definition`（LONGTEXT，`V26__orchestration_engine.sql:8-26`），执行时才 `objectMapper.readValue(definition, OrchestrationGraph.class)`；Spring Boot 默认 ObjectMapper 对未知字段宽容。这意味着：**画布坐标等前端扩展键可随 JSON 原文透传存储，执行端忽略未知字段，零执行链改造**。已知缺陷是"存储即真相"，任何结构性校验都必须在保存/执行前补位。

**CRUD 完整性**：现有端点 `POST /graphs`、`GET /graphs`、`GET /graphs/{id}`、`PUT /graphs/{id}/activate`（仅状态切换）、`DELETE /graphs/{id}`、执行类（execute/stream/executions/pause/resume/cancel）、`POST /goals`。**缺 `PUT/PATCH /graphs/{id}` 更新端点**；`CreateGraphRequest` 四字段全部无 `@Valid`/校验注解，未遵循项目 Bean Validation 惯例（参照 `CreateAgentCommand`）。

**属性面板可引用的数据源盘点**（设计器下拉化的可行性核心）：

| 数据源 | 现状 | 设计器可用性 |
|---|---|---|
| Agent 列表（AGENT 节点 refId） | `GET /api/v1/agents`（分页）返回 agentId/agentName/description/modelProvider/modelName/status | ✅ 直接做下拉（名称+模型双列展示） |
| 模型配置（图变量兜底） | `GET /api/v1/models/active`、`GET /api/v1/models/providers`（前端已有 `lib/model-config.ts` 对接） | ✅ 直接做下拉 |
| roleCode | `RoleRegistry.defaultSdlcRoles()` 内置 12 角色（REQUIREMENT_PM/ARCHITECT/DEVELOPER/REFACTOR/CODE_REVIEW/SECURITY_AUDIT/TEST_ENGINEER/QA/DEVOPS/SRE/DATABASE_DESIGN/DOC_ENGINEER）+ `RoleConfigSource` SPI 扩展；**无 REST 端点** | ⚠️ 短期前端内置 12 角色常量，中期新增目录端点 |
| 工具目录（TOOL 节点 toolName） | 双源：`ToolRegistry.listDefinitions()`（@ToolProvider 代码工具）优先 + `agent_tool` 表（status=1，经 DbToolConfigSourceAdapter）；**无全量列表端点**（AgentToolController 仅按 id/按 Agent 查） | ⚠️ 需新增合并目录端点 |
| HUMAN 节点 refId（approvalConfigId） | **纯占位概念**：全库仅 `GraphNode.java:25` 注释命中，无实体/无表/无 API/运行时不消费；HUMAN 执行时审批 ID 为运行时随机数，实际仅消费 `config.timeoutSeconds`（默认 1800s） | ⚠️ 面板应置灰存档，勿引导填写 |
| executionMode | 枚举 REACT/PLAN_EXECUTE/REFLEXION/TOOL_PARALLEL 存在，但 PIPELINE 执行链不消费（运行时存在于 orchestration/runtime/ 但节点级选择未接线） | ⚠️ 面板可做下拉但需标注"暂未生效" |
| SUBGRAPH 节点 | 枚举存在、无专用执行逻辑（Pipeline 中落 default 按 AGENT 处理） | ⚠️ 节点库禁用并标注"引擎未实现" |

**校验设施**：自研 `SchemaValidator`（`gewu-agent-engine/.../tool/security/SchemaValidator.java:44-114`，支持 object/array/string/number/integer/boolean + required + 递归 properties，无 enum/oneOf）可复用做属性级校验；**全平台无图级 lint**（workflow 侧同样没有，甚至把缺失的 from/to 静默回填为按顺序串接）。挂载点明确：`OrchestrationService.deserializeGraph()` 之后、`FourPhasePipeline.executeGraph()`（L53-70）之前。

**执行态可视化基础**：SSE 事件（`AgentEvent.java:92-164`）中 `graph_start`、`node_start`（nodeId/role）、`node_complete`（nodeId/outputLen）、`approval_required`（approvalId/timeoutSeconds）、`approval_result`、`handoff`、`graph_complete`（终态 status/output）足以支撑画布节点高亮与审批锚点；AGENT 节点的子事件（thinking/content/tool_call/tool_result 等）在执行时也补写 nodeId/role，可精确归因。**事件不含坐标**，节点位置由前端从图定义映射。历史回放可用 `orchestration_node_execution` 表（nodeId/nodeType/roleCode/status/durationMs）落库数据。

### 3.3 两套引擎边界（重要前提）

`docs/design/28-workflow-engine-design.md` 开头"边界澄清（V1.2 修订，2026-08-27）"明确**"两轨保留、不再合并"**：

| | 工作流（workflow） | 编排（orchestration） |
|---|---|---|
| 定位 | 人工审批流（人通过 completeNode 推进） | Agent DAG（引擎自主推进） |
| 存储 | 7 张表，节点含 positionX/positionY 列 | JSON 原文（graph_definition LONGTEXT） |
| 图保存 API | `PUT /{id}`、`PUT /{id}/graph` 完整（但无校验） | **缺更新端点** |
| 前端 | WorkflowCanvas（审批流画布） | 仅 JSON 文本框 |

**本设计器只服务 orchestration**；workflow 的"更新端点 + 坐标落库"方案可作为实现参照。前端文案必须区分："审批流画布" vs "智能体编排设计器"，避免用户混淆两套概念。

---

## 四、用户与需求分析

### 4.1 用户画像

| 画像 | 描述 | 现状体验 |
|---|---|---|
| P1 集成开发者 | 懂 JSON 与 Agent 概念，主要构建者 | 勉强可用，但属性靠翻代码/文档，低效易错 |
| P2 运维/业务配置员 | **痛点主体**：不写 JSON，理解流程但不理解 schema | 被完全挡在门外 |
| P3 售前/演示 | 需要可视化表达力做方案呈现 | 无可用工具 |

### 4.2 核心用户故事

- **US1**（P1）：我想拖拽节点、连线即可生成编排图，不必记忆 JSON 属性。
- **US2**（P2）：属性面板对 Agent/工具/角色/模型提供**下拉选择**，而非手填 ID 字符串。
- **US3**（P2）：保存时得到**结构校验报告**（悬空边/缺条件/环），而不是执行时才报错。
- **US4**（P2）：执行时能在画布上看到**哪个节点在运行/卡在审批**。
- **US5**（全体）：从**预置模板**（如"需求→架构→开发→测试"SDLC 链）一键创建再微调。

### 4.3 功能需求清单

| 优先级 | 编号 | 需求 | 说明 |
|---|---|---|---|
| P0 | FR-01 | 画布编排 | 左侧节点库拖入、端口连线、选中/删除/右键菜单、缩放平移 |
| P0 | FR-02 | 属性面板 | 按节点类型渲染字段（§7.4 字段规范），数据源下拉化 |
| P0 | FR-03 | JSON 双向同步 | 画布 ↔ Monaco JSON 双视图，单一数据源归一转换 |
| P0 | FR-04 | 保存 | 依赖后端新增更新端点；激活前可反复编辑 |
| P0 | FR-05 | 实时校验 | 前端 lint + 校验面板（§7.5 规则），保存/激活双闸 |
| P0 | FR-06 | 模式感知 | 四种模式各自的画布语义（§7.3），是本设计器区别于通用画布的差异点 |
| P0 | FR-07 | 坐标持久化 | 节点位置随 JSON 透传存储（§7.2） |
| P0 | FR-08 | 存量缺陷修复 | D1-D5 随本期一并修复（placeholder、文案、mode 生效、更新端点、校验） |
| P1 | FR-09 | 执行态可视化 | SSE 驱动节点高亮/审批锚点/终态着色 |
| P1 | FR-10 | 模板库 | 3-5 个预置图（SDLC 链、研究接力、监督分派、辩论） |
| P1 | FR-11 | 自动布局 | elkjs/dagre，存量无坐标图打开时兜底 |
| P1 | FR-12 | 编辑入口 | 图列表 → 设计器编辑 |
| P2 | FR-13 | SUBGRAPH 嵌套子图 | 等引擎实现后 |
| P2 | FR-14 | 执行历史回放 | 按 OrchestrationNodeExecution 逐节点着色 |
| P2 | FR-15 | 画布增强 | minimap/框选/对齐吸附 |

### 4.4 非功能需求

- **NFR-01 主题一致**：全部颜色走 CSS 变量（ink/tech 等 token），四套主题可切换，禁硬编码（WorkflowCanvas 教训）。
- **NFR-02 内网部署**：零 CDN 外链，库资源随包构建（参照 Monaco 自托管先例）。
- **NFR-03 性能**：≥100 节点图拖拽流畅（React Flow 虚拟化可满足）。
- **NFR-04 兼容**：存量 JSON 图 100% 可打开（无坐标时自动布局兜底）。
- **NFR-05 权限**：沿用现有菜单/RBAC，不新增权限模型。

---

## 五、竞品范式参照

n8n、Node-RED、Dify、Coze、Langflow 的共性范式（本项目 `workflow-canvas-manual.md` 已明确以之为参照）：

1. 三区布局：**左节点库 / 中画布 / 右属性面板**；
2. JSON/代码双视图（面向高级用户与导入导出）；
3. 保存时结构校验 + 错误定位（点击错误跳到对应节点/边）；
4. 运行态高亮（执行中节点呼吸动画、成功/失败着色）；
5. 节点级"未配置"角标（必填项缺失时节点卡片显示警示）。

其中 Dify、Langflow、Flowise 的画布底层均为 **React Flow**——佐证引入成熟图库的社区成熟度与范式同构性。

---

## 六、方案比选

| 维度 | A：JSON Schema 结构化表单（无画布） | B：增强自研 WorkflowCanvas | C：引入 @xyflow/react 新建设计器 | **D：C 画布 + JSON 双视图 + 表单（推荐）** |
|---|---|---|---|---|
| 工作量 | 小（3-5 人日） | 中（10-15 人日） | 中（8-12 人日） | 中（10-14 人日，分期） |
| 能力上限 | 低：解决"记不住属性"，不解决"看不清结构/门槛" | 中：需自补持久化/撤销/运行态/性能，天花板低 | 高：滚轮缩放/框选/minimap/连线交互开箱 | 高 |
| 风险 | 低 | 中：重演 demo 级坑（不持久化、假按钮） | 低-中：版本迭代快（v11→v12 有 breaking） | 低-中 |
| 与现有范式一致性 | 一般 | 高（复用手册交互） | 高（范式同构） | 高 |
| 对 P2 用户友好度 | 中 | 高 | 高 | 高 |

**推荐 D，理由**：
1. 画布解决"结构可视化"（US1/US4），下拉化属性面板解决"记不住属性"（US2），JSON 双视图保留专家通道与兜底能力（导入导出、复杂 variables、排查）；
2. React Flow（@xyflow/react v12，MIT）纯 React 组件、无外部静态资源（内网友好）、自定义节点即 React 组件、CSS 变量易适配 ink/tech 主题、`dynamic ssr:false` 接入 Next 成熟；
3. 复用 `workflowTypes.ts` 的节点目录思想与 `ConfigField` 声明式表单模式，降低表单开发成本；
4. 单一数据源 = 图 JSON 对象，画布操作与手改 JSON 归一化走同一转换层，避免双源漂移。

---

## 七、推荐方案总体设计

### 7.1 信息架构

嵌入现有 `OrchestrationPage` 做视图切换（列表 ↔ 设计器，参照 WorkflowPage→WorkflowCanvas 惯例），**不新增菜单项**。入口两处：
- 创建弹窗："创建并设计"（创建 draft 后直接进入设计器）；
- 列表卡片："设计"按钮（FR-12，draft 可编辑；active 图提示先取消激活或走版本化策略，见开放问题）。

### 7.2 数据模型与双向映射

单一数据源为图 JSON 对象（`OrchestrationGraph` 形状）。映射：

| 图定义 JSON | React Flow | 备注 |
|---|---|---|
| `nodes[i].nodeId` | `node.id` | 图内唯一 |
| `nodes[i].x / nodes[i].y`（新增可选字段） | `node.position` | 存储为 JSON 原文透传，执行端 Jackson 忽略未知字段，**零执行链改造**；同时在 `GraphNode.java` 补字段注释文档化 |
| `nodes[i]` 其余字段 | `node.data`（自定义节点卡片渲染 type 图标/名称/未配置角标） | |
| `edges[i].fromNode / toNode` | `edge.source / target` | |
| `edges[i].condition` | `edge.label` / `edge.data.condition` | 仅 ROUTER 出边展示编辑入口 |
| `variables / mode / type / name` | 画布级设置面板 | |

转换层（`graphTransformer.ts`，建议）：`toReactFlow(graph): {nodes, edges}` 与 `toGraphDefinition(nodes, edges, settings): graph` 一对互逆函数，画布与 JSON 视图都只读写它。

### 7.3 模式感知（本设计器的差异点）

| 模式 | 画布行为 |
|---|---|
| PIPELINE | 全功能画布；ROUTER 出边强制 condition（缺 else 兜底边给警告）；PARALLEL/MERGE 配对提示；AGENT 节点多出边提示"仅取第一条" |
| SWARM | 边编辑降级/隐藏，面板提示"路由由 Agent 输出的 `HANDOFF:目标\|理由` / `FINISH:理由` 指令决定，边不参与执行"；节点卡片提示防环上限（maxHandoffs 默认 8） |
| SUPERVISOR | 首个 AGENT 节点渲染"监督者"徽标（调整声明顺序即更换监督者，需醒目提示）；边编辑同 SWARM 降级 |
| DEBATE | 建议下拉补上该第四模式（后端已支持）；提示需 MERGE/ROUTER 节点作裁判 |

### 7.4 属性面板字段规范（核心表）

| 节点类型 | 字段 → 控件 | 数据源 / 校验 / 生效状态 |
|---|---|---|
| AGENT | refId → Agent 下拉；roleCode → 角色下拉；executionMode → 下拉（标注"暂未生效"）；inputs → KV 编辑器（提示 `message` 键覆盖前驱产出，其余键以"## 参考"追加）；config.outputSchema → Monaco JSON | `GET /api/v1/agents`；12 内置角色常量（B3 就绪后切端点）；refId 为空时用图变量 modelProvider/modelName 兜底 |
| TOOL | config.toolName → 工具目录下拉（必填）；refId 同步该值；arguments → Monaco JSON（支持 `${var}` 占位提示）；outputVar → 文本（缺省 nodeId） | B3 新目录端点（ToolRegistry + agent_tool 合并） |
| HUMAN | refId → 置灰（占位说明）；timeoutSeconds → 数字 1-86400（默认 1800） | 运行时仅 timeoutSeconds 生效；审批类型当前硬编码 APPROVE_REJECT |
| ROUTER | 无自身属性字段；condition 在**出边**上编辑 → 表达式 helper（`var:x == 'y'` / `!=` / `contains` / 裸变量 / `else` 兜底） | RouteConditionEvaluator 语法；缺条件无法命中 |
| PARALLEL / MERGE | MERGE：strategy → 下拉（默认拼接 / `json_merge`） | 需成对使用 |
| PLAN | goalType → 下拉（默认 FEATURE） | 依赖 GoalPlanner Bean，嵌套深度 ≤1 |
| SUBGRAPH | 节点库禁用 + "引擎未实现"标注 | 当前按 AGENT 处理 |
| 画布级 | name；mode（四模式下拉）；type（AD_HOC 等）；variables → KV（提示 `input` 为运行时用户输入、`modelProvider`/`modelName` 兜底）；rootGoalId（一般留空） | GraphType / OrchestrationMode 枚举 |

### 7.5 校验层（前端 lint VL-01~12，后端同规则双闸）

| 编号 | 规则 | 级别 |
|---|---|---|
| VL-01 | nodeId 唯一且非空 | 错误 |
| VL-02 | 边的 fromNode/toNode 引用存在（悬空边） | 错误 |
| VL-03 | PIPELINE：存在无入边起始节点；AGENT/HUMAN 链无环；ROUTER 出边必含 condition；PARALLEL/MERGE 成对 | 错误/警告 |
| VL-04 | SWARM：至少 1 个 AGENT 节点 | 错误 |
| VL-05 | SUPERVISOR：至少 1 个 AGENT 节点（首节点即监督者） | 错误 |
| VL-06 | TOOL：config.toolName 必填且在工具目录中 | 错误 |
| VL-07 | type/executionMode/mode/graphType 枚举合法 | 错误 |
| VL-08 | HUMAN：timeoutSeconds ∈ [1, 86400] | 警告 |
| VL-09 | inputs/arguments 中 `${var.xxx}` 引用可解析（指向图变量或前驱 nodeId 变量） | 警告 |
| VL-10 | outputSchema 可解析为合法 JSON Schema 形状 | 错误 |
| VL-11 | DEBATE：存在 MERGE/ROUTER 裁判节点 | 警告 |
| VL-12 | mode 与图结构一致性提示（如 SWARM 图却画了边） | 警告 |

后端：新增 `GraphDefinitionValidator`（application 层），在**保存时**与**执行前**（`deserializeGraph()` 之后、`FourPhasePipeline.executeGraph()` 之前）双闸执行；属性级校验复用自研 `SchemaValidator`；校验失败返回结构化错误清单（含 nodeId 定位），前端可点击跳转。

### 7.6 执行态可视化（P1，FR-09）

复用 `lib/orchestration.ts` 的 SSE 通道（POST fetch + getReader 手工解析，含 AbortController）：
`node_start` → 运行中高亮（呼吸动画）；`node_complete` → 成功着色 + 耗时角标；`approval_required` → 审批锚点（联动审批 API）；`handoff` → 动态连线动画（SWARM 模式）；`graph_complete` / `error` → 整图终态与失败节点红色 + 错误 tooltip。

### 7.7 后端配套改造清单

| 编号 | 改造 | 说明 |
|---|---|---|
| B1 | `PUT /api/v1/orchestration/graphs/{graphId}` | 仅 draft 可编辑（或激活后修改版本 +1，见开放问题）；请求体补 `@Valid` 与校验注解；保存前执行图 lint |
| B2 | `GraphDefinitionValidator` | VL-01~12 服务端实现，保存 + 执行前双闸 |
| B3 | 目录端点 | `GET /api/v1/orchestration/catalog/roles`（RoleRegistry）+ `GET .../catalog/tools`（ToolRegistry 与 agent_tool 合并）；短期可先由前端内置常量 |
| B4 | mode 生效修复 | 保存时将所选模式规范化写入 JSON `mode` 字段（单一代码路径）；执行链保持"JSON 内 mode 为准"；存量兼容：JSON 无 mode 时回填实体列值 |
| B5 | 创建弹窗修复 | 修正 placeholder（真实 schema 示例）、去掉"可选"标注、mode 提示文案 |
| B6 | 坐标支持 | GraphNode 补 x/y 可选字段注释；存量图无坐标时前端 elkjs 自动布局兜底并提示保存 |

---

## 八、技术选型要点

| 维度 | @xyflow/react v12（React Flow） | AntV X6 | 自研增强 |
|---|---|---|---|
| License | MIT | MIT | — |
| React 集成 | 原生 React 组件，自定义节点即组件 | JSX 模板桥接 | 手写 |
| 主题适配 | CSS 变量/类名覆写，易匹配 ink/tech | CSS 变量 | 完全自主但成本高 |
| 内网部署 | 无外部静态资源 ✅ | ✅ | ✅ |
| 生态佐证 | Dify/Langflow/Flowise 同款 | 国内文档好 | WorkflowCanvas 已示范自维护成本 |
| 主要风险 | 大版本 breaking（v11→v12），需锁版本 | 包体较大 | 持续投入 |

- 画布底座：**@xyflow/react v12**，pnpm 安装并锁版本；`next/dynamic` + `ssr:false` 引入。
- 自动布局：**elkjs**（P1，FR-11）。
- JSON 视图：Monaco（已自托管）+ `setDiagnosticsOptions` 挂图定义 JSON Schema——全项目首次启用 JSON Schema 提示，独立小任务，Schema 文件可由 §附录A 生成。

---

## 九、里程碑与工作量估算

| 里程碑 | 内容 | 估算 |
|---|---|---|
| M0 后端配套 | B1-B5（含 D1-D5 修复） | 3-5 人日 |
| M1 设计器 MVP | 画布 + 属性面板 + JSON 双向 + 保存 + lint（FR-01~08） | 8-12 人日 |
| M2 执行态 + 模板 | FR-09/10 | 4-6 人日 |
| M3 体验增强 | 自动布局 / 模式专属视图 / FR-11/12 | 3-5 人日 |

**合计约 18-28 人日。**

实施约束（依据 AGENTS.md 统一调度）：
- 界面生成叠加 **UI-STD-001**（`ui-design/UI-RULES.md`），产出过 U-CHK 25 项自检；
- 编码叠加 **CODING-STD-001**（安全红线 + 分语言规则）；
- 代码产出过 **CR-RULES** P0/P1 质量门（L3-SEC-*）；
- 全程 **AETC** 留痕，M0 涉及既有接口修改属 R2 操作（GATE_REQUEST + 检查点）。

---

## 十、风险与开放问题

**风险**：

| # | 风险 | 缓解 |
|---|---|---|
| R1 | mode 生效修复改变存量图行为 | 兼容策略：仅当 JSON 无 mode 字段时回填实体列值；发布说明明示 |
| R2 | React Flow 大版本 breaking | 锁定版本，升级走独立任务 |
| R3 | 与审批流画布（workflow）概念混淆 | 命名"编排设计器"，文案区分"审批流"与"智能体编排" |
| R4 | HUMAN.refId 无实体支撑，用户误以为可配置 | 面板置灰 + 说明文案 |
| R5 | executionMode/SUBGRAPH 未接线却出现在 UI | 标注"暂未生效/未实现"，防误导 |

**开放问题**（需产品/架构拍板）：
1. 图定义版本化策略：激活后允许修改（版本 +1）还是禁止修改？
2. SUBGRAPH 嵌套子图引擎排期，是否影响设计器节点库取舍？
3. DEBATE 模式是否随设计器一并暴露到前端（建议是）？

---

## 附录A：图定义 JSON 属性全表

### A.1 顶层结构（OrchestrationGraph.java:25-39）

```json
{
  "name": "需求-审批-开发流水线",
  "mode": "PIPELINE",
  "type": "AD_HOC",
  "nodes": [],
  "edges": [],
  "variables": { "input": "", "modelProvider": "openai", "modelName": "gpt-4o" },
  "rootGoalId": null
}
```

| 字段 | 类型 | 必填 | 含义 |
|---|---|---|---|
| name | String | 建议 | 图名称（弹窗"名称"字段单独存储，JSON 内可重复） |
| mode | OrchestrationMode | **执行时以此为准** | `SUPERVISOR` / `PIPELINE`（缺省默认）/ `SWARM` / `DEBATE` |
| type | GraphType | 可选 | `SDLC_PIPELINE` / `GOAL_DECOMPOSED` / `AD_HOC`（推荐临时图）/ `TEMPLATE` |
| nodes | List<GraphNode> | 是（可为空数组） | 节点列表 |
| edges | List<GraphEdge> | 是（可为空数组） | 边列表（SWARM/SUPERVISOR 忽略） |
| variables | Map<String,Object> | 可选 | 图级变量；执行时自动注入 `input`=用户输入；AGENT 节点 refId 为空时用 `modelProvider`/`modelName` 兜底解析模型 |
| rootGoalId | String | 可选 | 关联自主目标 ID |
| graphId | String | 可选 | 图 ID（服务端一般自行生成） |

### A.2 节点属性（GraphNode.java:19-35）

| 属性 | 类型 | 必填 | 含义 |
|---|---|---|---|
| nodeId | String | **是** | 图内唯一 ID，边通过它引用（**没有 `id`/`name` 字段**，placeholder 曾误导） |
| type | NodeType | 否（缺省按 AGENT） | `AGENT` / `TOOL` / `HUMAN` / `ROUTER` / `PARALLEL` / `MERGE` / `PLAN` / `SUBGRAPH`（未实现） |
| refId | String | 按 type | AGENT→agentId（可空，走变量兜底）；TOOL→工具名；HUMAN→审批配置 ID（**当前占位不消费**）；SUBGRAPH→子图 ID |
| roleCode | String | 否 | AGENT 节点角色编码（12 内置 + SPI），运行时作为事件元数据（node_start 的 role） |
| executionMode | ExecutionMode | 否（缺省 REACT） | `REACT` / `PLAN_EXECUTE` / `REFLEXION` / `TOOL_PARALLEL`（**当前执行链未消费**） |
| config | Map | 按 type | 见 A.3 |
| inputs | Map | 否 | 输入映射，支持 `${var.xxx}` 模板；**键 `message` 覆盖前驱产出**，其余键以"## 参考"段追加 |

### A.3 config 常用键（按实际消费点）

| 节点类型 | 键 | 类型 | 必填 | 含义 |
|---|---|---|---|---|
| TOOL | toolName | String | **是**（缺失报 NODE_CONFIG_INVALID） | 工具名 |
| TOOL | arguments | Object | 否 | 工具参数，支持 `${var}` 占位 |
| TOOL | outputVar | String | 否（缺省 nodeId） | 产出写入的变量名 |
| HUMAN | timeoutSeconds | int | 否（默认 1800） | 审批等待超时 |
| MERGE | strategy | String | 否（默认拼接） | `json_merge` 时按 JSON 合并 |
| AGENT | outputSchema | Object/String | 否 | 输出契约校验（字段列表/Schema Map/逗号分隔字符串） |
| PLAN | goalType | String | 否（默认 FEATURE） | 目标类型 |

### A.4 边属性（GraphEdge.java:17-25）

| 属性 | 类型 | 含义 |
|---|---|---|
| fromNode | String | 起始节点 ID |
| toNode | String | 目标节点 ID |
| condition | String | 路由条件，**仅对 ROUTER 节点的出边生效**；语法：`var:x == 'y'` / `!=` / `contains` / 裸变量名 / `else`（兜底边）；非默认条件短路命中；无 order 字段，多出边顺序即数组声明顺序 |

### A.5 三种（四种）编排模式对图结构的要求

| 模式 | nodes 要求 | edges | 空图行为 |
|---|---|---|---|
| PIPELINE | 任意类型混合 | **驱动执行**：起始=无入边节点；AGENT 多出边取第一条；ROUTER 出边需 condition | SUCCESS |
| SWARM | 只看 AGENT 节点，按声明顺序 | **完全忽略**；路由靠输出 `HANDOFF:`/`FINISH:` 指令；防环 maxHandoffs=8 | 无 AGENT → SUCCESS |
| SUPERVISOR | 只看 AGENT 节点，**第一个是监督者** | **完全忽略**；顺序串行 + 委托信封（TASK_DELEGATE/TASK_RESULT） | 无 AGENT → SUCCESS |
| DEBATE（前端未暴露） | AGENT 为辩手 | 取第一个 MERGE/ROUTER 作裁判 | — |

### A.6 可执行示例

PIPELINE（含 HUMAN 审批与 TOOL）：

```json
{
  "name": "需求-审批-开发流水线",
  "mode": "PIPELINE",
  "type": "AD_HOC",
  "nodes": [
    { "nodeId": "n1", "type": "AGENT", "roleCode": "REQUIREMENT_PM", "executionMode": "REACT", "x": 80, "y": 120 },
    { "nodeId": "n2", "type": "HUMAN", "refId": "approval-arch", "config": { "timeoutSeconds": 1800 }, "x": 340, "y": 120 },
    { "nodeId": "n3", "type": "AGENT", "roleCode": "DEVELOPER", "x": 600, "y": 120 },
    { "nodeId": "n4", "type": "TOOL", "refId": "git_commit", "config": { "toolName": "git_commit", "outputVar": "commitResult" }, "x": 860, "y": 120 }
  ],
  "edges": [
    { "fromNode": "n1", "toNode": "n2" },
    { "fromNode": "n2", "toNode": "n3" },
    { "fromNode": "n3", "toNode": "n4" }
  ],
  "variables": { "modelProvider": "openai", "modelName": "gpt-4o" }
}
```

SWARM（边留空，路由靠指令）：

```json
{
  "name": "调研接力",
  "mode": "SWARM",
  "type": "AD_HOC",
  "nodes": [
    { "nodeId": "a1", "type": "AGENT", "roleCode": "REQUIREMENT_PM" },
    { "nodeId": "a2", "type": "AGENT", "roleCode": "ARCHITECT" },
    { "nodeId": "a3", "type": "AGENT", "roleCode": "DOC_ENGINEER" }
  ],
  "edges": []
}
```

SUPERVISOR（第一个 AGENT 即监督者）：

```json
{
  "name": "重构分派",
  "mode": "SUPERVISOR",
  "type": "AD_HOC",
  "nodes": [
    { "nodeId": "s0", "type": "AGENT", "roleCode": "ARCHITECT" },
    { "nodeId": "s1", "type": "AGENT", "roleCode": "DEVELOPER" },
    { "nodeId": "s2", "type": "AGENT", "roleCode": "TEST_ENGINEER" }
  ],
  "edges": []
}
```

---

## 附录B：关键引用文件清单

**前端（gewu-web/src/）**
- `components/pages/OrchestrationPage.tsx` — 编排引擎页（创建弹窗 L375-413、placeholder L397、SSE 面板 L288-319）
- `components/pages/WorkflowCanvas.tsx` — 既有自绘画布（658 行，demo 级）
- `components/pages/workflowTypes.ts` — 节点目录 + ConfigField 表单 schema（可复用模式）
- `lib/orchestration.ts` — 图 CRUD + SSE（executeGraphStream L146-193）
- `lib/request.ts` / `lib/api.ts` — axios 封装（baseURL /api）
- `lib/themes.ts` / `components/ThemeSync.tsx` / `tailwind.config.ts` — 主题 token（ink/tech）
- `components/ui/Select.tsx` — CustomSelect；`components/ui/Toast.tsx`
- `app/page.tsx` / `types/index.ts` / `components/layout/Sidebar.tsx` / `lib/menu.ts` — 页面注册与菜单
- `components/pages/MonacoEditor.tsx` / `lib/fileLanguage.ts` — Monaco 自托管接入

**后端引擎（gewu-agent-engine/src/main/java/com/gewu/agent/engine/orchestration/）**
- `model/OrchestrationGraph.java`、`model/GraphNode.java`、`model/GraphEdge.java` — 图 JSON schema
- `model/OrchestrationMode.java`、`model/NodeType.java`、`model/ExecutionMode.java`、`model/GraphType.java` — 枚举
- `Orchestrator.java` — 模式分派（L103-118）
- `mode/PipelineModeHandler.java`、`mode/SwarmModeHandler.java`、`mode/SupervisorModeHandler.java`、`mode/DebateModeHandler.java` — 四模式执行器
- `HandoffParser.java`、`RouteConditionEvaluator.java`、`VariableTemplates.java` — 指令/条件/模板解析
- `role/RoleRegistry.java` — 12 内置角色（L61-188）
- `tool/ToolRegistry.java`、`tool/ToolProvider.java` — 代码工具注册
- `tool/security/SchemaValidator.java` — 可复用的属性级校验器
- `core/event/AgentEvent.java` — 事件类型常量（L92-164）
- `config/AgentEngineAutoConfiguration.java` — Bean 装配（L490-585）

**应用与接口层**
- `gewu-application/.../orchestration/OrchestrationService.java` — createGraph（L67-86）、deserializeGraph（L528-541）、executeGraph/Stream
- `gewu-application/.../governance/FourPhasePipeline.java` — 执行挂载点（L53-70）
- `gewu-interface/.../controller/OrchestrationController.java` — REST 端点（L34-151，缺更新端点）
- `gewu-interface/.../controller/AgentController.java`、`ModelConfigController.java`、`AgentToolController.java` — 属性面板数据源
- `gewu-domain/.../orchestration/OrchestrationGraphEntity.java`、`OrchestrationNodeExecutionEntity.java` — 实体
- `gewu-interface/src/main/resources/db/migration/V26__orchestration_engine.sql` — 建表
- `gewu-domain/.../workflow/WorkflowNode.java`（positionX/Y 参照）、`WorkflowController.java`（PUT graph 参照）

**文档**
- `docs/agent-engine/07-orchestration.md` — 编排引擎说明（模式速览 L97-102）
- `docs/design/28-workflow-engine-design.md` — V1.2 两轨边界澄清
- `docs/design/29-agent-orchestration-engine.md` — 编排引擎设计
- `docs/workflow-canvas-manual.md` — 画布交互范式参照

---

*报告完 · V1.0 · 2026-09-24*
