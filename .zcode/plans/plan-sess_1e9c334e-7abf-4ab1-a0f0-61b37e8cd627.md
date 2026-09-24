# 编排图可视化设计器 · 调研分析报告

> 批准后将本报告完整落盘为 `docs/design/46-orchestration-graph-visual-designer-research.md`（含附录完整表格），并按 AETC 留痕。

## 一、背景与问题定义

当前创建编排图唯一入口是"手写 JSON 文本框"（`gewu-web/src/components/pages/OrchestrationPage.tsx:397-402`），使用者必须记住 `OrchestrationGraph/GraphNode/GraphEdge` 的全部属性与含义才能写出可执行的定义。调研确认了 5 个已存在的可用性缺陷：

| # | 缺陷 | 证据 |
|---|------|------|
| D1 | placeholder 键名错误：示例 `{"id":"n1","name":"步骤一"}` 与后端 schema（`nodeId` 等）不匹配，按示例填写得到 nodeId=null 的不可执行图 | OrchestrationPage.tsx:397 vs GraphNode.java:19-35 |
| D2 | 标注"图定义 JSON（可选）"，但后端强制非空（"编排图定义不能为空"） | OrchestrationService.java:70-72 |
| D3 | 下拉框选择的"编排模式"只存 `orchestration_mode` 列展示，执行时从不注入解析结果，引擎按 JSON 内 `mode` 分派（缺省 PIPELINE）——选 SWARM/SUPERVISOR 不写进 JSON 就不生效 | OrchestrationService.java:528-541（deserializeGraph 无注入）、Orchestrator.java:109 |
| D4 | 无编辑页、后端无更新端点（无 `PUT /graphs/{id}`），创建后无法补填/修改定义，只能删除重建 | OrchestrationController.java 全端点清单 |
| D5 | 全链路零校验：创建仅查非空；JSON 解析推迟到执行时；无图结构 lint（悬空边/环/缺条件），未知字段被静默忽略 | OrchestrationService.java、全库无 GraphValidator |

## 二、调研范围与方法

- 范围：前端 `gewu-web`（画布/组件/路由/主题/请求层）、后端 `gewu-agent-engine`/`gewu-application`/`gewu-interface`（编排模型、CRUD、引用数据源、校验设施、执行事件）、设计文档 28/29/31 号与 `docs/workflow-canvas-manual.md`。
- 方法：双路并行代码勘察（Explore 子代理）+ 关键结论定点复核（OrchestrationService 执行链、Controller 端点清单均为直接读码确认）。

## 三、现状盘点

### 3.1 前端现状
- **技术栈**：Next.js 14.2（App Router 但实际为 `PageType` 单页映射）+ React 18 + TS 5 + Tailwind 3 + Redux Toolkit（仅导航/主题/登录态，业务页面全用局部 useState）+ axios 封装 `lib/request.ts`；**未引入任何图/画布库**；dnd-kit 已装但仅用于列表排序。
- **组件体系**：无通用 Dialog/Input/Tabs/Tooltip 封装（弹窗为 fixed 遮罩 + glass-dark 卡片的页面内惯例）；手写 `CustomSelect`（ui/Select.tsx）、Toast；主题为 4 套 CSS 变量（ink/deepsea/jade/celadon，截图深绿即 ink 主题，主色 `tech-500 #00b894`）。
- **关键资产——已有工作流画布**：`WorkflowCanvas.tsx`（658 行，纯 SVG+绝对定位 div 自绘：贝塞尔连线、端口连接、节点拖拽、右键菜单、272px 属性抽屉按声明式 `ConfigField`（text/textarea/select/number）渲染）+ `workflowTypes.ts`（399 行，23 种节点目录，**最干净、可整体复用**）+ 508 行用户手册（明言参考 n8n/Node-RED/Dify/Coze）。但实现是 **demo 级**：画布内容不持久化、保存/撤销为假按钮、无运行态着色、无滚轮缩放/框选/自动布局、主题色硬编码。结论：**UX 范式与节点目录可复用，绘制层不建议深挖复用**。

### 3.2 后端现状（面向设计器的能力缺口）
- **图定义以 JSON 原文存 `graph_definition` LONGTEXT**，执行时才 `readValue(OrchestrationGraph.class)`，且 Jackson 对未知字段宽容 → **画布坐标等扩展键可随 JSON 透传存储，零后端模型改造**。
- **CRUD 缺口**：无更新端点；`CreateGraphRequest` 无 `@Valid`/校验注解（未遵循项目 Bean Validation 惯例）。
- **属性面板数据源盘点**：

| 数据源 | 现状 | 设计器可用性 |
|---|---|---|
| Agent 列表（AGENT.refId） | `GET /api/v1/agents` 返回 agentId/agentName/modelProvider/modelName/status | ✅ 直接做下拉 |
| 模型（variables 兜底） | `GET /api/v1/models/active`、`/models/providers` | ✅ 直接做下拉 |
| roleCode | RoleRegistry 内置 12 角色（REQUIREMENT_PM/ARCHITECT/DEVELOPER/REFACTOR/CODE_REVIEW/SECURITY_AUDIT/TEST_ENGINEER/QA/DEVOPS/SRE/DATABASE_DESIGN/DOC_ENGINEER）+ SPI 扩展，**无 REST 端点** | ⚠️ 需新增目录端点或前端内置常量 |
| 工具目录（TOOL.toolName） | ToolRegistry.listDefinitions()（@ToolProvider 代码工具）+ agent_tool 表（status=1）两源，**无全量列表端点** | ⚠️ 需新增合并目录端点 |
| HUMAN.refId(approvalConfigId) | **纯占位概念**：无实体/无表/无 API/运行时不消费；实际仅 `config.timeoutSeconds` 生效（默认 1800s） | ⚠️ 面板应置灰存档，勿引导填写 |
| executionMode | 枚举存在但 PIPELINE 执行链不消费（未接线） | ⚠️ 面板可做下拉但需标注"暂未生效" |
| SUBGRAPH 节点 | 枚举存在、无专用执行逻辑（当 AGENT 处理） | ⚠️ 面板禁用并标注 |

- **校验设施**：自研 `SchemaValidator`（tool/security/，支持基础类型+required+递归 properties，无 enum/oneOf）可复用做属性级校验；**全平台（含 workflow 侧）无图级 lint**；挂载点明确：`OrchestrationService.deserializeGraph` 之后、`FourPhasePipeline.executeGraph`（L53-70）之前。
- **执行态可视化基础**：SSE 事件 `node_start/node_complete`（含 nodeId/role）、`approval_required/approval_result`、`handoff`、`graph_complete` 足以支撑画布节点高亮与审批锚点（AGENT 子事件 thinking/content/tool_* 也带 nodeId 可精确归因）；历史回放可用 `OrchestrationNodeExecutionEntity` 落库数据。事件不含坐标，位置由前端从定义映射。

### 3.3 两套引擎边界（重要前提）
`docs/design/28-workflow-engine-design.md` V1.2（2026-08-27）明确**"两轨保留、不再合并"**：工作流=人工审批流（7 张表存储、positionX/positionY 列、`PUT /{id}/graph` 完整但无校验甚至静默回填串接），编排=Agent DAG（JSON 定义）。**本设计器只服务 orchestration**；workflow 的"更新端点 + 坐标落库"方案可作为实现参照。前端文案须区分："审批流画布" vs "智能体编排设计器"。

## 四、用户与需求分析

**用户画像**：P1 集成开发者（懂 JSON/Agent 概念，当前勉强可用但低效）；P2 运维/业务配置员（**痛点主体**：不会手写 JSON，被完全挡在门外）；P3 售前/演示（需要可视化表达力）。

**核心用户故事**：
- US1 集成开发者：拖拽节点+连线即可生成编排图，不必记忆 JSON 属性。
- US2 配置员：属性面板对 Agent/工具/模型/角色提供下拉选择，而非手填 ID 字符串。
- US3 配置员：保存时得到结构校验报告（悬空边/缺条件/环），而不是执行时才报错。
- US4 配置员：执行时能在画布上看到哪个节点在运行/卡在审批。
- US5 团队：从预置模板（如"需求→开发→测试"SDLC 链）一键创建再微调。

**功能需求清单**：

| 优先级 | 编号 | 需求 |
|---|---|---|
| P0 | FR-01 | 画布编排：左侧节点库拖入、端口连线、选中/删除/右键菜单 |
| P0 | FR-02 | 属性面板：按节点类型渲染字段（见 §7.4 字段规范），数据源下拉化 |
| P0 | FR-03 | JSON 双向同步：画布 ↔ Monaco JSON 视图（Monaco 已自托管，可加 JSON Schema 提示） |
| P0 | FR-04 | 保存：依赖后端新增更新端点；激活前可反复编辑 |
| P0 | FR-05 | 前端实时 lint + 校验面板（规则见 §7.5） |
| P0 | FR-06 | 模式感知：四种模式各自的画布语义（见 §7.3） |
| P0 | FR-07 | 坐标持久化：位置随 JSON 透传存储 |
| P0 | FR-08 | 顺带修复 D1-D5 五个已知缺陷 |
| P1 | FR-09 | 执行态可视化：SSE 驱动节点高亮/审批锚点/终态着色 |
| P1 | FR-10 | 模板库（3-5 个预置图） |
| P1 | FR-11 | 自动布局（elkjs/dagre），存量无坐标图打开时兜底 |
| P1 | FR-12 | 图列表 → 设计器编辑入口 |
| P2 | FR-13 | SUBGRAPH 嵌套子图（等引擎实现后） |
| P2 | FR-14 | 执行历史回放（按 OrchestrationNodeExecution 逐节点着色） |
| P2 | FR-15 | minimap/框选/对齐吸附 |

**非功能需求**：NFR-01 四套主题一致（CSS 变量，禁硬编码）；NFR-02 内网部署零 CDN 外链；NFR-03 ≥100 节点拖拽流畅；NFR-04 存量 JSON 图 100% 兼容打开；NFR-05 权限沿用现有菜单/RBAC。

## 五、竞品范式参照

n8n、Node-RED、Dify、Coze、Langflow 的共性范式（本项目手册已明确以之为参照）：**左节点库 / 中画布 / 右属性面板** 三区布局 + JSON/代码双视图 + 保存校验 + 运行态高亮。其中 Dify/Langflow/Flowise 底层均为 **React Flow**——佐证引入成熟图库的社区成熟度。

## 六、方案比选

| 维度 | A：JSON Schema 结构化表单（无画布） | B：增强自研 WorkflowCanvas | C：引入 @xyflow/react 新建设计器 | **D：C 画布 + JSON 双视图 + 表单（推荐）** |
|---|---|---|---|---|
| 工作量 | 小（3-5 人日） | 中（10-15 人日，补持久化/撤销/运行态） | 中（8-12 人日） | 中（10-14 人日分期） |
| 能力上限 | 低：解决"记不住属性"，不解决"看不清结构" | 中：自绘层补齐成本高，性能/交互天花板低 | 高：成熟交互（滚轮缩放/框选/minimap 开箱） | 高 |
| 风险 | 低 | 中：重演 demo 级坑 | 低-中：版本迭代快（v11→v12 breaking） | 低-中 |
| 与现有范式一致性 | 一般 | 高（复用手册交互） | 高（范式同构） | 高 |

**推荐 D**：以 React Flow（@xyflow/react v12，MIT，纯 React 组件无外部资源，`dynamic ssr:false` 接入 Next）为画布底座，复用 `workflowTypes.ts` 的节点目录思想与 `ConfigField` 声明式表单模式；右侧属性面板 + 底部/抽屉 Monaco JSON 双视图双向同步（单一数据源为图 JSON 对象，画布操作与手改 JSON 均归一化走同一转换层）；老图、复杂场景、导入导出走 JSON 视图兜底。

## 七、推荐方案总体设计

### 7.1 信息架构
嵌入现有 `OrchestrationPage` 做视图切换（列表 ↔ 设计器，参照 WorkflowPage→WorkflowCanvas 惯例），不新增菜单项；入口两处：创建弹窗"创建并设计"、列表卡片"设计"。

### 7.2 数据模型与双向映射
单一数据源 = 图 JSON 对象；映射：`nodes[] ↔ ReactFlow Node{id:nodeId, position, data}`、`edges[] ↔ ReactFlow Edge{id, source:fromNode, target:toNode, label/data.condition}`；坐标存 `GraphNode.x/y`（新增可选字段，LONGTEXT 透传存储，执行端 Jackson 忽略未知字段，**零执行链改造**；同时在 GraphNode.java 补字段注释文档化）。

### 7.3 模式感知（本设计器的差异点）
- **PIPELINE**：全功能画布；ROUTER 出边强制 condition（缺 else 兜底边给警告）；PARALLEL/MERGE 配对提示。
- **SWARM**：边编辑降级/隐藏并提示"路由由 Agent 输出的 HANDOFF:/FINISH 指令决定，边不参与"；节点卡片提示防环上限 maxHandoffs=8。
- **SUPERVISOR**：首个 AGENT 节点渲染"监督者"徽标（拖动排序即换监督者，需醒目提示）；边编辑同 SWARM 降级。
- **DEBATE**（建议下拉补上该第四模式）：提示需 MERGE/ROUTER 节点作裁判。

### 7.4 属性面板字段规范（核心表）

| 节点类型 | 字段 → 控件 | 数据源/校验 |
|---|---|---|
| AGENT | refId→Agent 下拉；roleCode→角色下拉；executionMode→下拉（标注"暂未生效"）；inputs→KV 编辑器（提示 message 键覆盖前驱产出）；config.outputSchema→Monaco JSON | GET /api/v1/agents；12 内置角色常量（后端目录端点就绪后切换） |
| TOOL | config.toolName→工具目录下拉（必填）；arguments→Monaco JSON（${var} 提示）；outputVar→文本 | 新目录端点（ToolRegistry+agent_tool 合并） |
| HUMAN | refId→置灰（占位）；timeoutSeconds→数字 1-86400 | 运行时仅 timeoutSeconds 生效 |
| ROUTER | 无自身字段；condition 在出边上编辑→表达式 helper（var:x ==/!=/contains/else） | RouteConditionEvaluator 语法 |
| MERGE | strategy→下拉（默认拼接 / json_merge） | PipelineModeHandler |
| PLAN | goalType→下拉（默认 FEATURE） | GoalPlanner |
| SUBGRAPH | 禁用 + "引擎未实现"标注 | — |
| 画布级 | name/mode(四模式)/type(AD_HOC 等)/variables→KV（提示 input=运行时用户输入，modelProvider/modelName 兜底）/rootGoalId | GraphType/OrchestrationMode 枚举 |

### 7.5 校验层（前端 lint VL-01~12，后端同规则双闸）
VL-01 nodeId 唯一非空；VL-02 边引用存在性；VL-03 PIPELINE：有起始节点、环检测、ROUTER 出边必含条件、PARALLEL/MERGE 配对；VL-04 SWARM ≥1 AGENT；VL-05 SUPERVISOR ≥1 AGENT（首节点即监督者）；VL-06 TOOL toolName 必填且在目录中；VL-07 枚举合法性；VL-08 HUMAN timeoutSeconds 范围；VL-09 inputs 中 ${var.xxx} 引用可解析；VL-10 outputSchema 可解析；VL-11 DEBATE 有裁判节点；VL-12 mode 与图结构一致性提示。后端：新增 `GraphDefinitionValidator` 在保存与执行前（deserializeGraph 后）双闸执行，复用自研 SchemaValidator 做属性级校验。

### 7.6 执行态可视化（P1）
复用 `lib/orchestration.ts` 的 SSE 通道：node_start→运行中高亮（呼吸动画）、node_complete→成功着色+耗时、approval_required→审批锚点（跳转审批 API）、handoff→动态连线动画、graph_complete→整图终态；失败节点红色+错误 tooltip。

### 7.7 后端配套改造清单
B1 `PUT /api/v1/orchestration/graphs/{graphId}`（仅 draft 可编辑或版本+1，补 @Valid）；B2 图定义校验服务（VL 规则，保存+执行前双闸）；B3 目录端点：角色（RoleRegistry.listAll）+ 全量工具（ToolRegistry + agent_tool 合并）；B4 **mode 生效修复**：保存时将所选模式规范化写入 JSON `mode` 字段（单一代码路径，兼容存量）；B5 创建弹窗修复（D1 placeholder、D2 文案、D3）；B6 GraphNode 补 x/y 字段文档 + 存量图无坐标时 elkjs 自动布局兜底。

## 八、技术选型要点

@xyflow/react v12 vs AntV X6 vs 自研增强：React Flow 胜在 React 原生定制节点、CSS 变量易适配 ink/tech 主题、无外部静态资源（内网友好，符合 Monaco 自托管先例）、与 Dify/Langflow 同源生态；注意锁定版本、用 pnpm 安装。自动布局选 elkjs（P1）。JSON 提示：Monaco `setDiagnosticsOptions` 挂图定义 JSON Schema（全项目首次启用，独立小任务）。

## 九、里程碑与工作量估算

| 里程碑 | 内容 | 估算 |
|---|---|---|
| M0 后端配套 | B1-B5（含 5 缺陷修复） | 3-5 人日 |
| M1 设计器 MVP | 画布+属性面板+JSON 双向+保存+lint（FR-01~08） | 8-12 人日 |
| M2 执行态+模板 | FR-09/10 | 4-6 人日 |
| M3 体验增强 | 自动布局/模式专属视图/FR-11/12 | 3-5 人日 |

合计约 **18-28 人日**。实施须叠加 ui-design/UI-RULES.md（U-CHK 25 项自检）、CODING.md，产出过 CR P0/P1 质量门，全程 AETC 留痕。

## 十、风险与开放问题

风险：R1 mode 语义修复影响存量图行为（需兼容策略：仅当 JSON 无 mode 时注入）；R2 React Flow 大版本 breaking（锁版本）；R3 与审批流画布文案混淆（命名"编排设计器"）；R4 HUMAN.refId 无实体支撑（面板置灰防误导）；R5 executionMode 未接线（标注防误解）。
开放问题：图定义是否版本化（激活后改版本+1 还是禁止改）？SUBGRAPH 实现排期？DEBATE 是否随设计器一并暴露到前端？

## 十一、附录（报告文件中含完整版）

A. 图定义 JSON 属性全表（顶层 8 字段 / 节点 7 字段 + 各类型 config 键 / 边 3 字段 / condition 语法）；B. 引用文件清单（约 30 个关键 file:line）。