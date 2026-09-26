# 格物平台 · 编排引擎操作手册

| | |
|---|---|
| 文档编号 | GEWU-MANUAL-ORCH-001 |
| 版本 | V1.1（2026-09-26） |
| 适用对象 | 使用编排引擎构建与运行多智能体协作流程的产品 / 运营 / 开发人员 |
| 适用范围 | 格物智能体平台 Web 端「编排引擎」功能 + 编排引擎 Open API |
| 代码基线 | 分支 `fix/sandbox-security-storage`（含 O1~O3 能力补全：版本化 / 检查点持久化 / 节点重试超时 / SUBGRAPH / 触发体系），对应 docs/design/48 与 EXEPLAN-ORCH-2026-09 |
| V1.1 变更 | 版本化与下架回滚（§5）、SUBGRAPH 实现（§6.3.8）、变量语法统一（§8.3）、VL-13/14（§9）、检查点持久化与续跑 UI 闭环（§10.4）、触发体系（§10.7）、节点级重试/超时（§13.5）、API 与配置项增补 |

---

## 目录

1. [功能概述](#1-功能概述)
2. [核心概念](#2-核心概念)
3. [快速入门：十分钟跑通第一个编排图](#3-快速入门十分钟跑通第一个编排图)
4. [功能入口与列表页总览](#4-功能入口与列表页总览)
5. [编排图生命周期管理](#5-编排图生命周期管理)
6. [可视化设计器详解](#6-可视化设计器详解)
7. [四种编排模式详解](#7-四种编排模式详解)
8. [图变量与变量引用](#8-图变量与变量引用)
9. [结构校验规则（VL-01 ~ VL-12）](#9-结构校验规则vl-01--vl-12)
10. [运行与监控](#10-运行与监控)
11. [人工审批（HITL）](#11-人工审批hitl)
12. [自主目标（Autonomous Goal）](#12-自主目标autonomous-goal)
13. [失败处理与容错](#13-失败处理与容错)
14. [API 参考](#14-api-参考)
15. [数据持久化与可观测性](#15-数据持久化与可观测性)
16. [常见问题 FAQ](#16-常见问题-faq)
17. [典型场景示例](#17-典型场景示例)
- [附录 A：节点类型速查表](#附录-a节点类型速查表)
- [附录 B：内置角色清单（RoleCode）](#附录-b内置角色清单rolecode)
- [附录 C：创建模板清单](#附录-c创建模板清单)
- [附录 D：相关配置项](#附录-d相关配置项)

---

## 1. 功能概述

编排引擎是格物智能体平台的多智能体协作调度中枢。它把"多个 Agent / 工具 / 人工环节按什么顺序、以什么方式协作"抽象为一张**编排图（Graph）**，提供从**可视化设计 → 结构校验 → 激活发布 → 执行监控 → 暂停/断点续跑/取消 → 执行回放**的全流程能力。

功能组成：

| 子功能 | 说明 | 主要载体 |
|---|---|---|
| 可视化设计器 | 拖拽式画布编排：节点库 / 画布 / 属性面板 / JSON 双向同步 / 实时校验 / 触发配置 | Web 端设计器页面 |
| 执行引擎 | 按四种编排模式调度节点执行，支持条件路由、并行汇聚、动态规划、嵌套子图 | 后端编排引擎（`gewu-agent-engine`） |
| 执行管理 | 同步 / 流式（SSE）执行、暂停 / 恢复 / 取消、执行历史与回放 | 列表页 + 设计器运行预览 |
| 版本管理 | 激活即发布不可变版本快照；执行绑定版本；下架回草稿与版本回滚 | 列表页版本历史弹窗 |
| 触发体系 | 手动 / API / Agent 会话工具化 / 定时（Cron）/ Webhook 五类触发 | §10.7 |
| 人工审批 | 图中嵌入 HUMAN 审批节点（可圈定审批人），阻塞等待审批中心裁决后继续 | 审批中心 + HITL 网关 |
| 自主目标 | 输入一个目标描述，由引擎自动"分解 → 执行 → 验收"循环直至完成 | Open API（SSE） |

典型使用场景：

- **固定流程交付**：需求梳理 → 开发 → 审查 → 提交，用流水线（Pipeline）模式串成固定链路；
- **多视角决策**：架构选型、方案评审，让多个 Agent 并行出方案、由裁判节点汇总裁决（Debate 模式）；
- **探索性调研**：任务边界不明确，由 Agent 之间自主接力（Swarm 模式）；
- **有风险把关的自动化**：在关键节点插入人工审批，审批通过才继续执行；
- **动态任务分解**：只给目标描述，由 PLAN 节点或自主目标 API 自动拆解为子任务并行执行。

---

## 2. 核心概念

| 概念 | 说明 |
|---|---|
| **编排图（Graph）** | 一次协作流程的完整定义，包含节点列表、边列表、编排模式、图变量等，以 JSON 存储于 `orchestration_graph` 表 |
| **节点（Node）** | 流程中的一个执行单元，共 8 种类型（AGENT / TOOL / HUMAN / ROUTER / PARALLEL / MERGE / PLAN / SUBGRAPH），每个节点有图内唯一的 `nodeId` |
| **边（Edge）** | 节点间的有向连线（`fromNode → toNode`），决定执行走向；可携带路由条件 `condition` |
| **编排模式（Mode）** | 图的调度策略：PIPELINE / SUPERVISOR / SWARM / DEBATE 四选一 |
| **图类型（GraphType）** | 图的业务分类标签：AD_HOC（默认）/ SDLC_PIPELINE / GOAL_DECOMPOSED / TEMPLATE |
| **执行实例（Execution）** | 编排图的一次运行记录，含状态、当前节点、最终输出、Token 消耗等 |
| **节点执行记录** | 每个节点在一次执行中的状态、耗时、错误信息，是执行回放的数据源 |
| **图变量（Variables）** | 图级初始变量，进入执行上下文；`input` 为运行时用户输入 |
| **节点产出变量** | 每个节点执行完成后，其产出自动写入以其 `nodeId` 命名的上下文变量，供下游节点引用 |
| **HITL（人工介入）** | Human-in-the-Loop：HUMAN 节点阻塞等待人工在审批中心批准 / 驳回 |
| **自主目标（Goal）** | 一段自然语言目标，引擎自动分解为计划图并循环"执行 → 验收 → 反思"直至完成或超限 |

---

## 3. 快速入门：十分钟跑通第一个编排图

以下用最短路径跑通一个"开发者 Agent 处理输入并输出"的流水线。

**第 1 步：创建编排图**
1. 左侧导航进入 **工作空间 → 编排引擎**；
2. 点击右上角 **创建编排图**；
3. 填写名称（如「我的第一个流水线」），模板选 **空白图（推荐）**，编排模式保持 **流水线（Pipeline）**，图定义 JSON 留空；
4. 点击 **创建** —— 创建成功后自动进入可视化设计器。

**第 2 步：搭建流程**
1. 从左侧节点库把 **Agent 节点** 拖入画布（自动编号 n1、n2…）；
2. 再拖入一个 **工具节点**（或第二个 Agent 节点）；
3. 从第一个节点底部端口按住拖到第二个节点顶部端口，完成连线（箭头方向 = 执行方向）。

**第 3 步：配置节点**
1. 单击选中 Agent 节点，在右侧属性面板：
   - **Agent（refId）**：下拉选择平台已有的智能体实例；也可选「不指定」，运行时用图变量兜底模型（见 §8.3）；
   - **角色（roleCode）**：下拉选择角色，如「开发者（DEVELOPER）」；
2. 选中工具节点，在右侧面板选择 **工具（toolName）**（必填，否则校验不通过）。

**第 4 步：保存并激活**
1. 点击顶部 **校验** 查看结构问题；ERROR 级问题（红标）会阻断保存，需先处理；
2. 点击 **保存**；
3. 返回列表页（顶部 **返回** 按钮），点击该图卡片上的 **激活** —— 激活后图变为只读、可执行。

**第 5 步：执行与观察**
1. 在列表页点击卡片上的 ▶（同步执行）或 ⚡（流式执行），可在下方"执行事件"面板实时看到 `[graph_start]`、`[node_start]`、`[node_complete]`、`[graph_complete]` 等事件；
2. 或再次进入设计器点击 **运行**：画布节点会随执行实时着色（运行中青色 / 成功绿色 / 失败红色 / 等待审批金色），底部"运行预览"控制台滚动展示事件日志与最终输出；
3. 在列表页底部 **执行历史** 中查看每次执行的状态、Token 消耗与当前节点。

**第 6 步（可选）：回放**
在设计器中点击 **回放**，选择一条历史执行并 **加载到画布**，画布节点将按该次执行的结果着色，并显示各节点耗时与错误信息。

---

## 4. 功能入口与列表页总览

**入口**：左侧导航 **工作空间 → 编排引擎**。

列表页自上而下分为四个区域：

### 4.1 统计卡片

| 卡片 | 含义 |
|---|---|
| 编排图总数 | 全部编排图数量（含草稿与已激活） |
| 已激活 | 处于 active 状态、可执行的编排图数量 |
| 运行中执行 | 当前状态为 RUNNING 的执行实例数量 |
| 累计执行 | 全部执行实例数量 |

### 4.2 搜索与筛选

- **搜索框**：按编排图名称模糊过滤；
- **状态下拉**：所有状态 / 草稿 / 已激活；
- **刷新执行** 按钮：重新拉取执行历史列表。

### 4.3 编排图列表

每张卡片展示：名称、状态徽标（草稿 / 已激活）、版本号（当前固定 v1）、编排模式、图 ID、创建时间。卡片右侧操作按钮：

| 按钮 | 可用条件 | 行为 |
|---|---|---|
| **设计 / 查看** | 始终可用 | 进入可视化设计器；草稿图可编辑，已激活图只读（可运行预览与回放） |
| **激活** | 仅草稿显示 | 状态 draft → active（见 §5.3） |
| **▶ 同步执行** | 仅已激活 | 阻塞式执行，完成后 Toast 提示结果状态；有最终输出时展示在执行事件面板 |
| **⚡ 流式执行** | 仅已激活 | SSE 流式执行，事件实时滚动展示在"执行事件"面板 |
| **🗑 删除** | 始终可用 | 逻辑删除编排图，**并级联删除其全部执行实例与审批请求**（见 §5.4） |

> **执行事件面板**：点击流式执行后出现，含输入框（可填执行输入）与"再次执行"按钮，事件按 `[事件类型] 节点ID 内容` 格式滚动展示（保留最近 200 条）。

### 4.4 执行历史

按创建时间倒序列出执行实例：执行 ID、所属图 ID、开始时间、Token 消耗、当前节点、状态徽标（等待中 / 运行中 / 已暂停 / 已成功 / 已失败 / 已取消）。

对仍在生命周期内的执行（RUNNING / PAUSED）提供两个控制按钮：

- **暂停**（RUNNING 时）／ **恢复**（PAUSED 时）；
- **取消**。

状态机与操作语义详见 §10.3、§10.4。

---

## 5. 编排图生命周期管理

### 5.1 创建

点击列表页右上角 **创建编排图**，弹窗字段：

| 字段 | 必填 | 说明 |
|---|---|---|
| 名称 | ✅ | 编排图显示名称 |
| 从模板开始 | ❌ | 选 **空白图**（推荐，进设计器拖拽）或 6 个内置模板之一（清单见附录 C）；选模板后自动填入图定义并联动编排模式 |
| 编排模式 | ❌ | 默认 PIPELINE；选模板时自动带出模板模式 |
| 图定义 JSON | ❌ | 高级用法：直接粘贴图定义 JSON；留空则创建 `{"nodes":[],"edges":[]}` 空图 |

创建成功后**自动进入设计器**。后端约定：图定义不能为空，因此界面在留空时自动以空图骨架兜底。

### 5.2 编辑（设计）

- **仅草稿（draft）状态可编辑**。已激活图进入设计器为只读模式（工具栏显示「已激活 · 只读」徽标，保存按钮禁用），但仍可运行预览与执行回放；
- 保存时后端会再次执行图结构校验，**ERROR 级问题阻断保存**（VL 规则见 §9）；
- 画布内容与 JSON 视图双向同步（见 §6.7）。

### 5.3 激活（draft → active，发布版本快照）

- 点击列表卡片 **激活** 按钮。激活前会执行结构校验（ERROR 阻断）；后端要求图定义非空才能激活；
- **激活即发布不可变版本快照**：每次激活把当前定义发布为版本 v1、v2…（同图自增），此后执行优先加载最新版本快照——之后再激活新版本，不影响既有执行记录的回放一致性；
- 执行记录会绑定触发时的版本 ID（versionId），执行历史与回放按该版本呈现。

### 5.4 下架与版本回滚（active ↔ draft）

已激活的图不再需要删图重建：

| 操作 | 入口 | 行为 |
|---|---|---|
| **下架** | 列表卡片「下架」按钮 | active → draft，重新可编辑；已有执行与审批数据全部保留 |
| **版本历史** | 列表卡片 🕘 按钮 | 弹窗展示全部版本快照（版本号 / 模式 / 激活时间），支持与当前草稿**双栏定义对比** |
| **回滚** | 版本历史弹窗「回滚」 | 将指定版本快照写回草稿定义（仅 draft 可回滚，active 图请先下架）；回滚后需重新激活才可执行 |

### 5.5 删除

- 逻辑删除编排图本身，并**级联逻辑删除**其全部执行实例、节点级执行记录与审批请求；
- 删除不可恢复，请谨慎操作（建议通过版本历史回滚替代删除）。

### 5.6 状态模型

```
            创建
             │
             ▼
          ┌──────┐   激活（发布版本快照）   ┌────────┐
          │ draft │ ─────────────────────▶ │ active │
          └──────┘ ◀───────────────────── └────────┘
                    下架（保留执行历史）
```

| 状态 | 界面显示 | 可编辑 | 可执行（界面） |
|---|---|---|---|
| draft | 草稿 | ✅ | ❌（可在设计器内"保存并运行"预览；定时/Webhook/Agent 工具均要求 active） |
| active | 已激活 | ❌ 只读（下架后恢复可编辑） | ✅ |

---

## 6. 可视化设计器详解

### 6.1 界面布局

```
┌──────────────────────────────────────────────────────────────┐
│ 工具栏：返回 | 名称 | 编排模式 | 图类型 | 整理布局 运行 回放 校验 JSON 保存 │
├──────────────────────────────────────────────────────────────┤
│（校验结果面板：点击「校验」按钮展开，点击问题可定位节点）                │
├────────┬────────────────────────────────┬────────────────────┤
│ 节点库  │           画布（React Flow）     │      属性面板        │
│ 8 种   │  拖拽编排 / 连线 / 框选 / 缩放    │  节点属性 / 连线属性  │
│ 节点   │  左下控制按钮 / 右下小地图        │  / 画布级设置        │
└────────┴────────────────────────────────┴────────────────────┘
│（运行预览 / 执行回放面板：从底部展开，高 16rem）                     │
└──────────────────────────────────────────────────────────────┘
```

### 6.2 画布基础操作与快捷键

| 操作 | 方式 |
|---|---|
| 添加节点 | 从左侧节点库**拖拽**到画布任意位置（自动按 n1、n2… 顺序编号） |
| 连线 | 从**源节点底部端口**按住拖到**目标节点顶部端口** |
| 选中节点 / 连线 | 单击；选中后右侧属性面板显示对应表单 |
| 框选多节点 | **Shift + 左键拖拽** |
| 删除选中 | **Delete** 或 **Backspace**（删除节点时级联删除其关联连线） |
| 缩放 | 鼠标滚轮 |
| 平移 | 按住画布空白处拖拽 |
| 网格吸附 | 默认开启，16px 网格 |
| 小地图 | 右下角 MiniMap，可拖拽 / 缩放；节点颜色实时反映运行态 |
| 整理布局 | 工具栏 **整理布局** 按钮：按拓扑分层自动排布（层 = 自起始节点的最长路径深度），完成后自适应视野 |

> **运行态着色**（运行预览与回放共用）：运行中 = 青色，成功 = 绿色，失败 = 红色，等待审批 = 金色，未运行 = 默认墨绿。小地图颜色同步。

### 6.3 节点类型详解

左侧节点库共 8 种类型，其中 7 种已实现可直接拖拽使用，SUBGRAPH 置灰禁用。

#### 6.3.1 AGENT —— Agent 节点

把任务委派给某个智能体执行（LLM 推理节点），是最常用的节点类型。

| 属性 | 说明 |
|---|---|
| **Agent（refId）** | 下拉选择平台「我的智能体」中的实例（显示为 `名称（模型商/模型）`）。选「不指定」时，运行时以图变量 `modelProvider` / `modelName` 兜底解析模型（见 §8.3） |
| **角色（roleCode）** | 下拉选择角色（数据源为角色目录：内置 12 个 SDLC 角色 + SPI 扩展，见附录 B），决定该节点的角色人格 / 系统提示 |
| **输入映射（inputs）** | JSON 对象。约定键 `message` 会**覆盖**前驱节点产出作为任务输入；其余键的值以「## 参考：键名」段落追加到任务输入。值支持变量模板（见 §8） |
| **输出契约（outputSchema）** | JSON Schema 对象或逗号分隔字段串（如 `title,content`）。同步执行路径会校验节点产出是否符合契约，不符合则整图失败 |
| **重试与超时** | 折叠配置（见 §13.5）：`retryCount`（0-3，默认 0 不重试）、`retryBackoffMs`（默认 1000）、`timeoutSeconds`（缺省 0 不启用节点级超时） |

> executionMode 字段已从属性面板移除（引擎暂未接线，避免无效配置误导；后续版本接线后恢复）。

执行行为：产出写入以 `nodeId` 命名的上下文变量，并作为下游节点的默认输入；执行过程的事件（思考、内容、工具调用等）实时透传到 SSE 流。

#### 6.3.2 TOOL —— 工具节点

直接调用平台工具，**无 LLM 推理**，速度快、结果确定。

| 属性 | 说明 |
|---|---|
| **工具（config.toolName）** | ✅ 必填。下拉数据源为工具目录 = 代码级工具（`[代码]` 前缀）+ 配置化工具（`[配置]` 前缀，来自 `agent_tool` 表启用项）合并去重 |
| **工具参数（arguments）** | JSON 对象，支持 `${变量名}` 占位符引用上下文变量（见 §8） |
| **产出变量名（outputVar）** | 工具结果写入的上下文变量名，缺省写入以 `nodeId` 命名的变量 |

#### 6.3.3 HUMAN —— 人工审批节点

阻塞等待人工在**审批中心**批准 / 驳回后继续，用于关键动作把关。

| 属性 | 说明 |
|---|---|
| 审批配置（refId） | 暂不可配置（置灰），当前仅记录不参与执行 |
| **审批超时（timeoutSeconds）** | 等待审批的最大时长，默认 1800 秒（30 分钟），合法范围 [1, 86400]，超出范围产生 WARNING |
| **指定审批人（assigneeId）** | 用户 ID。填写后该审批请求**仅对此人可见**（审批中心过滤），留空=全员可见 |
| **指定审批角色（assigneeRole）** | 角色编码，与审批人并用：该角色成员亦可见可办 |

执行行为：执行到该节点时发出 `approval_required` 事件并阻塞；运行预览出现金色提示条「节点 xxx 等待人工审批」；处理方式见 §11。**批准** → 继续执行后续节点；**驳回** → 上下文回滚一版并整图失败；**超时未处理** → 按超时结束。⚠️ 审批等待中的执行**无法被暂停 / 取消信号中断**（信号在节点边界才检查）。

#### 6.3.4 ROUTER —— 条件路由节点

按**出边上的条件表达式**选择一条分支继续，实现 if/else 分叉。ROUTER 本身无产出，选中它时属性面板仅提示去连线编辑条件。

- 每条出边**必须**填写 `condition`（缺失为 ERROR，阻断保存）；
- 求值顺序：按出边声明顺序逐条短路求值；全部未命中时走**无条件 / else 边**兜底；若连兜底边都没有，整图失败（「ROUTER 节点无可命中出边」）；
- 建议始终保留一条 `else` 兜底边；被跳过分支的下游若接了 MERGE，引擎会自动扣减其等待入边数，不会卡死。

条件语法详见 §6.4。

#### 6.3.5 PARALLEL —— 并行扇出节点

同时触发**全部出边**分支并发执行，用于并行放大。出边少于 2 条时产生 WARNING（无实际并行意义）。典型配合：PARALLEL → 多个 AGENT → MERGE。

#### 6.3.6 MERGE —— 汇聚合并节点

等待**全部入边分支到齐**后合并产出，与 PARALLEL 配对使用。入边少于 2 条时产生 WARNING。

| 属性 | 说明 |
|---|---|
| **合并策略（strategy）** | 默认：按入边顺序将各分支产出以空行拼接为文本；`json_merge`：将各分支 JSON 对象按字段合并（适合结构化汇总） |

#### 6.3.7 PLAN —— 动态规划节点

把该节点的输入交给 **GoalPlanner（目标规划器）** 动态拆解为子计划，并按依赖关系分波次并行执行，实现图内的"动态任务分解"。

| 属性 | 说明 |
|---|---|
| 目标类型（goalType） | 默认 FEATURE；决定推荐编排模式与预算配额 |

执行行为：发出 `plan_created` 事件（前端展示计划步骤卡片）；子计划嵌套上限 1 层（PLAN 内不再嵌 PLAN）。规划器可配置为 LLM 规划（`agent.engine.planner.llm.enabled=true`，默认步数上限 8），未开启时退化为"整目标单节点执行"。

#### 6.3.8 SUBGRAPH —— 嵌套子图节点

把一张**已激活**的编排图作为子流程嵌入当前图执行，实现图复用与分层编排。

| 属性 | 说明 |
|---|---|
| **子图（refId）** | ✅ 必填。下拉选择已激活的编排图（自动排除当前图自身） |

执行语义：

- **沙箱上下文**：以「子图自身 variables + 父图变量快照（同名覆盖）」为初始变量执行；子图内部新写入的变量**不回渗父图**，子图最终产出作为本节点输出（写入以 nodeId 命名的变量）继续父图遍历；
- **深度上限 2**：子图内还可以再嵌一层子图，更深直接失败；
- **断点续跑**：从 SUBGRAPH 节点恢复时子图整体重跑（子图内部无断点语义）；
- 校验（VL-13）：refId 必填、禁止自引用、目标图必须已激活、跨图引用链不成环（A 引 B、B 引 A 会被阻断）；
- ⚠️ **依赖服务端开关** `agent.engine.orchestration.subgraph.enabled`（默认关闭）：关闭时节点按 AGENT 执行（历史行为）。

### 6.4 连线与路由条件

单击任意连线，右侧属性面板显示：

- **连线两端**（`源节点 → 目标节点`）；
- **路由条件（condition）**：仅对 **ROUTER 节点的出边**生效，其他连线上填写不产生影响；
- **删除连线** 按钮。

**条件表达式语法**（受限表达式，非任意脚本，防注入）：

| 写法 | 含义 | 示例 |
|---|---|---|
| `var:名称 == '值'` | 变量等值比较（值可带单/双引号或裸写；数字按数值比较） | `var:decision == 'APPROVED'` |
| `var:名称 != '值'` | 变量不等 | `var:status != 'FAILED'` |
| `var:名称 contains '子串'` | 变量值包含子串 | `var:n1 contains '通过'` |
| `var:名称` | 裸变量：存在且非空即真 | `var:reviewResult` |
| `else`（大小写不敏感）或留空 | 默认路由（兜底边） | `else` |

条件中引用的"变量"即图上下文变量：上游节点产出（key = 节点 ID）、图变量、运行输入 `input`。

### 6.5 属性面板总览

属性面板按当前选中对象渲染三种视图：

1. **节点表单**：按节点类型渲染对应字段（见 §6.3）；
2. **连线表单**：条件 + 删除（见 §6.4）；
3. **画布级设置**（未选中任何对象时显示）：
   - **失败传播（continueOnFailure）**：引擎默认（失败即整图终止）/ best-effort（失败继续其余分支）/ 显式失败终止。写入图变量 `continueOnFailure`，语义见 §13.2；
   - **图变量（variables）**：JSON 对象编辑器（见 §8）；
   - **关联自主目标（rootGoalId）**：填写自主目标 ID，将本图与一次自主目标执行关联；
   - **触发配置**（见 §10.7）：定时触发（Cron + 下次触发预览 + 启停）与 Webhook 触发（token 生成/重置/停用）。

### 6.6 节点卡片信息与徽标

画布上每个节点卡片展示：类型图标与名称、节点 ID、配置摘要（AGENT 显示角色 · Agent，TOOL 显示工具名，HUMAN 显示审批超时）与运行态图标。

- **关键配置缺失警示**：AGENT 节点既未指定 Agent 也未指定角色、或 TOOL 节点未选工具时，卡片右上角显示金色 ⚠ 角标；
- **SUPERVISOR 模式**下，图中**第一个 AGENT 节点**自动佩戴"监督者"徽标（随模式切换 / 节点增删实时重算）；
- 运行态着色见 §6.2。

### 6.7 JSON 视图

点击工具栏 **JSON** 按钮打开 JSON 面板：

- 打开时以当前画布状态生成格式化 JSON（`nodes` / `edges` / `mode` / `type` / `variables` / `rootGoalId`，节点坐标回写 `x` / `y`）；
- 编辑 JSON 时实时做语法校验（非法 JSON 显示错误且不允许应用）；
- 点击 **应用到画布** 后按 JSON 重建画布节点 / 连线，并同步模式、图类型与画布级设置——适合批量修改、从外部导入定义、或版本比对。

### 6.8 校验面板

点击工具栏 **校验** 展开实时校验结果（规则详见 §9）：

- 工具栏按钮上的徽标实时计数：红标 = ERROR 数，金标 = WARNING 数；
- ERROR 阻断保存与执行；WARNING 仅提示不阻断；
- 点击某条问题可直接定位（选中）对应节点。

### 6.9 工具栏其余按钮

| 按钮 | 行为 |
|---|---|
| 返回 | 回到列表页 |
| 名称输入框 | 修改编排图名称（随保存提交） |
| 编排模式下拉 | 切换 PIPELINE / SWARM / SUPERVISOR / DEBATE（语义见 §7） |
| 图类型下拉 | AD_HOC / SDLC_PIPELINE / GOAL_DECOMPOSED / TEMPLATE（业务分类标签） |
| 运行 | 见 §10.1（草稿图会先自动保存再执行） |
| 回放 | 见 §10.5 |
| 保存 | 保存当前画布为图定义（校验不通过则阻断并展开校验面板） |

---

## 7. 四种编排模式详解

编排模式决定引擎如何调度节点。**PIPELINE 与 DEBATE 模式下边结构参与执行；SWARM 与 SUPERVISOR 模式忽略边**（画布会显示金色提示条）。

### 7.1 流水线（PIPELINE）—— 默认模式

- **调度方式**：按边驱动的图遍历。从第一个**无入边**的节点开始（支持节点乱序声明），沿出边逐节点推进；上一节点的产出即下一节点的默认输入；
- **能力全集**：支持全部 8 种节点类型，条件路由、并行扇出/汇聚、人工审批、动态规划、协作式暂停/断点续跑（仅此模式支持）；
- **节点要求**：图不能有环（有环为 ERROR，执行无法终止）；ROUTER 出边必须带条件；
- **适用场景**：顺序明确的交付流程（FEATURE / BUGFIX 类）。

### 7.2 监督者（SUPERVISOR）

- **调度方式**：图中**第一个 AGENT 节点作为监督者**（画布佩戴徽标），其余节点按**声明顺序**串行执行；监督者与各专家的产出累积传递（`[节点ID] 产出`，以 `---` 分隔），每步委派与回传发出 `message` 结构化事件；
- **边结构**：不参与执行（连线仅作视觉参考，存在连线时校验给出 WARNING）；
- **节点要求**：至少 1 个 AGENT 节点（否则 ERROR）；
- **适用场景**：需要中央协调、任务边界不够清晰的场景（REFACTOR / OPS 类）。

### 7.3 群体协作（SWARM）

- **调度方式**：无中心节点，控制权在 Agent 之间**自主接力（handoff）**。从第一个 AGENT 节点开始，解析每个 Agent 的输出指令：
  - `FINISH`（可带 `:原因`）—— 立即结束；
  - `HANDOFF:目标节点ID|原因` —— 把控制权移交给目标 Agent（按 nodeId 或 refId 定位，目标不存在则回退声明顺序）；
  - 无指令 —— 按节点声明顺序传给下一个；
- **防失控**：接力次数上限 `maxHandoffs`（默认 8 次，超限强制终止）；已访问节点重复移交立即终止（防环）；
- **边结构**：不参与执行；
- **节点要求**：至少 1 个 AGENT 节点（否则 ERROR）；
- **适用场景**：探索性、路径不可预知的任务（RESEARCH 类）。

### 7.4 辩论共识（DEBATE）

- **调度方式**：全部 AGENT 节点作为辩手**并行**独立产出方案；图中第一个 MERGE 或 ROUTER 节点作为**裁判**，收到全部方案（以 `---方案分隔---` 连接）后综合裁决选出最优；无裁判时直接拼接各方方案输出；
- **轮次**：单轮辩论（无多轮对抗参数）；参与角色数 = 图中 AGENT 节点数；
- **失败语义**：某辩手失败以占位提案参与汇总（不影响其他辩手）；裁判失败整图失败；
- **节点要求**：建议包含 MERGE / ROUTER 节点作裁判（缺失 WARNING）；
- **适用场景**：架构选型、技术方案评审等多视角高风险决策。

### 7.5 模式选型速查

| 需求 | 推荐模式 | 关键特征 |
|---|---|---|
| 固定顺序流程、需要条件分支 / 并行 / 审批 | PIPELINE | 按边执行，功能最全 |
| 一个主导 Agent 统筹、专家依次接手 | SUPERVISOR | 声明顺序串行，忽略连线 |
| Agent 自主决定下一步给谁 | SWARM | 输出指令接力，忽略连线 |
| 多方案并行 + 裁决 | DEBATE | AGENT 并行 + MERGE/ROUTER 裁判 |

> 模式存储与生效：模式同时写入图定义 JSON 的 `mode` 字段（**执行时以此为准**）与图的 `orchestration_mode` 列；存量图 JSON 缺 mode 时回填列值，两者皆非法时回退 PIPELINE。

---

## 8. 图变量与变量引用

### 8.1 变量从哪里来

执行时构建上下文变量，来源与优先级（后者覆盖同名键）：

1. **图定义 variables**（画布级设置中的 JSON 对象）；
2. **运行时输入 `input`**（列表页 / 设计器 / API 传入的执行输入，固定写入 `input` 键）；
3. **节点产出**：每个节点完成后，产出写入以该节点 `nodeId` 命名的变量（TOOL 节点可经 `outputVar` 改名）。

### 8.2 在哪里引用变量

变量模板用于两类配置：

- **AGENT / PLAN 节点的 inputs**（输入映射）；
- **TOOL 节点的 arguments**（工具参数）。

### 8.3 引用语法（重要）

标准占位符语法是 **`${变量名}`**，变量名即上下文变量键：

| 写法 | 含义 |
|---|---|
| `${input}` | 引用运行时用户输入 |
| `${n1}` | 引用节点 `n1` 的产出（上游节点 ID） |
| `${modelProvider}` / `${modelName}` | 引用图变量兜底模型 |
| `${自定义变量}` | 引用图定义 variables 中的同名键 |

> **历史写法 `${var.xxx}` 已自动降级**：引擎查不到名为 `var.xxx` 的变量时，会去掉 `var.` 前缀按 `${xxx}` 重新解析（并记录告警日志）。新图请统一使用不带前缀的写法；VL-09 校验对两种写法同源生效（解析名去前缀）。未定义变量在运行时替换为空串并记录告警日志。

**内置约定变量**：

| 变量 | 说明 |
|---|---|
| `input` | 运行时用户输入 |
| `modelProvider` / `modelName` | 图变量。AGENT 节点未指定 refId 时的兜底模型 |
| `continueOnFailure` | 失败传播开关（见 §13.2） |
| `conflictCheck` | 同步执行路径开启多 Agent 产出冲突解决（权威优先 / 多数表决 / LLM 仲裁 / 人工裁决四策略） |

---

## 9. 结构校验规则（VL-01 ~ VL-14）

校验在**保存与执行前双闸**执行：前端画布实时提示 + 后端权威校验。**ERROR 阻断，WARNING 放行并记录日志**。前后端规则对齐（后端为权威）。

| 规则 | 级别 | 内容 |
|---|---|---|
| VL-01 | ERROR | 节点 ID 缺失或重复 |
| VL-02 | ERROR | 边引用了不存在的节点（悬空边） |
| VL-03 | ERROR/WARNING | PIPELINE 结构完整性：图为空（W）；无无入边起始节点，执行从第一个节点回退开始（W）；**图存在环**（E）；**ROUTER 出边缺少 condition**（E）；PARALLEL 出边 <2（W）；MERGE 入边 <2（W） |
| VL-04 | ERROR | SWARM 模式至少需要 1 个 AGENT 节点 |
| VL-05 | ERROR | SUPERVISOR 模式至少需要 1 个 AGENT 节点（首个即监督者） |
| VL-06 | ERROR | TOOL 节点缺少 `config.toolName` |
| VL-07 | ERROR | 枚举字段（节点类型 / 模式等）取值非法——由反序列化保证，报可读解析错误 |
| VL-08 | WARNING | HUMAN 节点 `timeoutSeconds` 超出 [1, 86400] 或不是数字 |
| VL-09 | WARNING | `${var.xxx}` 变量引用无法解析（未在图变量 / input / 节点产出中声明；见 §8.3 注意事项） |
| VL-10 | ERROR | AGENT 节点 `outputSchema` 不是合法 JSON Schema 或逗号分隔字段串 |
| VL-11 | WARNING | DEBATE 模式缺少 MERGE / ROUTER 节点作裁判 |
| VL-12 | WARNING | 当前模式忽略边结构（SWARM / SUPERVISOR 下存在连线） |
| VL-13 | ERROR | SUBGRAPH 节点 refId 缺失 / 自引用 / 目标图未激活 / 跨图引用链成环（查询异常降级 WARNING，运行时深度上限兜底） |
| VL-14 | WARNING | 节点级 retryCount 超出 [0,3] / timeoutSeconds 超出 [0, 86400] 或非数字（按边界值生效） |

---

## 10. 运行与监控

### 10.1 设计器运行预览（FR-09）

设计器内点击工具栏 **运行**（或运行控制台中的 **保存并执行 / 执行**）：

- **草稿图**：先自动保存（校验不通过会阻断），再流式执行；
- **已激活图**：直接运行**已保存版本**（画布未保存的改动不生效）；
- 底部展开 **运行预览控制台**：
  - 输入框：填写执行输入（可选）；
  - 事件日志：`[事件类型] 节点ID 内容` 格式滚动展示（保留最近 100 条）；
  - **画布节点实时着色**（见 §6.2），事件按 nodeId 归因到节点；
  - 等待审批时显示金色提示条（见 §11）；
  - 最终输出区：展示 `graph_complete` 携带的最终产出；
  - **停止** 按钮：中断 SSE 流并向后端发取消请求。

流异常中断时，引擎会把在途（运行中 / 等待审批）节点统一标红，避免画布停留在"运行中"假象。

### 10.2 列表页执行

- **同步执行 ▶**：阻塞直至执行完成，Toast 提示最终状态；有输出时展示在执行事件面板；
- **流式执行 ⚡**：SSE 实时执行，事件面板滚动展示；可在面板输入框填写执行输入后 **再次执行**。

### 10.3 执行生命周期与状态

```
PENDING（创建）
   │ 开始执行
   ▼
RUNNING ──暂停──▶ PAUSED ──恢复──▶ RUNNING
   │                 │
   ├──▶ SUCCEEDED    ├──▶ CANCELLED（取消）
   ├──▶ FAILED
   └──▶ CANCELLED（取消）
```

| 状态 | 界面显示 | 说明 |
|---|---|---|
| PENDING | 等待中 | 执行记录已创建 |
| RUNNING | 运行中 | 执行中，可暂停 / 取消 |
| PAUSED | 已暂停 | 已保存断点检查点，可恢复 / 取消 |
| SUCCEEDED | 已成功 | 图终态 SUCCESS |
| FAILED | 已失败 | 图终态 FAILED（含节点失败 / 校验失败 / 审批驳回 / 预算超限等） |
| CANCELLED | 已取消 | 用户取消；取消时变量终态快照写入执行记录留痕 |

> 执行记录的终态**以图执行的 `graph_complete` 事件状态为准**：失败图不会被误标为成功；失败原因（reason）写入执行记录的 errorMessage。

### 10.4 暂停 / 恢复（断点续跑）/ 取消

三个操作均为**协作式**控制：信号在**当前节点执行完毕后**（下一节点开始前）生效，不会打断正在执行的节点。

| 操作 | 前置状态 | 行为 |
|---|---|---|
| **暂停** | RUNNING | 保存断点检查点（图定义 + 上下文变量 + 恢复起点节点）→ 执行记录置 PAUSED → 事件流以 `graph_complete(PAUSED)` 优雅结束 |
| **恢复** | PAUSED | 界面已闭环两步合一：点击「恢复」后系统自动完成状态恢复 + 订阅断点续跑事件流，事件实时滚动到执行事件面板直至终态，无需再手动调续跑 API |
| **取消** | RUNNING / PAUSED | RUNNING：发协作取消信号，当前节点后优雅结束，取消时刻的变量终态快照写入执行记录；PAUSED：直接丢弃检查点并注销 |

> **检查点持久化**：暂停生效时检查点双写（内存 + `orchestration_checkpoint` 表），**服务进程重启后恢复依然可用**（恢复接口自动从持久层重建检查点、跳过已完成节点续跑）。仅当持久层也不可用时提示重新执行。

### 10.5 执行回放（FR-14）

设计器工具栏 **回放** 按钮：

1. 选择该图的一次历史执行（下拉项：执行 ID 前缀 · 状态 · 时间）；
2. 点击 **加载到画布**：按节点级执行记录为画布节点着色（成功绿 / 失败红 / 运行中青），并在面板下方网格中展示每个节点的状态、耗时（秒）与错误信息；
3. 点击 **清除着色** 恢复画布默认颜色；
4. 早期（节点级落库功能上线前）的历史执行无节点记录，会提示"该执行没有节点级记录"。

### 10.6 SSE 事件参考

三个流式端点（图执行 / 断点续跑 / 自主目标）推送统一的事件结构（type + content + nodeId + metadata 等）。常用事件：

| 事件 | 含义 |
|---|---|
| `graph_start` | 图开始执行（metadata 含 executionId / mode） |
| `node_start` / `node_complete` | 节点开始 / 完成（node_complete 的 metadata 可能含 routedTo、mergedFrom、outputLen 等） |
| `error` | 节点级错误（带 nodeId）或全局错误 |
| `graph_complete` | 图结束（metadata.status = SUCCESS / FAILED / PAUSED / CANCELLED，含 output / reason） |
| `content` / `thinking` / `tool_call` / `tool_result` / `done` | Agent 节点内部流式产出（内容 / 思考 / 工具调用 / 工具结果 / 完成） |
| `plan_created` / `plan_updated` | PLAN 节点或自主目标分解出计划步骤 |
| `handoff` | SWARM 接力（metadata 含 reason / target / depth） |
| `message` | SUPERVISOR 模式的委派 / 回传信封 |
| `approval_required` / `approval_result` | 审批请求 / 审批裁决结果 |
| `execution_paused` / `execution_cancelled` | 暂停（含恢复起点）/ 取消 |
| `goal_start` / `goal_decomposed` / `goal_complete` | 自主目标生命周期 |
| `reflection` / `verification_result` / `confidence_check` | 自主目标的反思 / 验收 / 置信度事件 |
| `budget_warning` / `budget_exceeded` | Token 预算 70% / 90% 警告 / 100% 超限 |

### 10.7 触发体系（五类触发方式）

编排图（须 active）除手动执行外，还有四类触发方式，执行记录通过 **triggerType** 区分（执行历史列表展示"手动/API/Agent工具/定时/Webhook 触发"）：

| 触发方式 | 配置入口 | 说明 |
|---|---|---|
| **手动 / API** | 列表页 ▶ / ⚡ 或 REST 调用 | triggerType=MANUAL |
| **Agent 工具化** | 无需配置 | 会话中 Agent 可自主调用内置工具 `run_orchestration_graph`（参数：graphId 必填 + input 可选），同步等待执行并把最终输出回传给 Agent 汇报；triggerType=AGENT_TOOL，执行记录挂靠发起会话（sessionId 贯通）。单次执行时长帽默认 300 秒（`agent.engine.tool.orchestration.timeout-seconds`） |
| **定时触发** | 设计器 → 画布级设置 → 触发配置 → 定时触发 | Spring Cron 6 位表达式（如 `0 0 9 * * *` 每天 9 点）+ 输入模板 + 启停；保存即校验并预计算**下次触发时间**；到期由调度器以系统身份发起执行（triggerType=SCHEDULE），失败不影响下轮调度。多实例部署通过 CAS 抢占防重复触发 |
| **Webhook 触发** | 设计器 → 画布级设置 → 触发配置 → Webhook | 生成 token（明文**仅生成时展示一次**，库内只存哈希），外部 `POST /api/v1/orchestration/webhooks/{token}` 即触发（body 原样作为输入）；token 可重置（旧 token 立即失效）与停用；错误/停用 token 统一返回 404。⚠️ 服务端总开关 `agent.engine.webhook.enabled` **默认关闭**，开启前须完成安全评审 |

---

## 11. 人工审批（HITL）

### 11.1 流程

```
HUMAN 节点开始执行
   → 创建审批请求（pending，带超时时间）
   → SSE 推送 approval_required（运行预览出现金色提示条，画布节点变金色）
   → 审批人在「系统管理 → 审批中心」处理
       ├─ 批准 → 节点继续，流程向下执行
       ├─ 驳回 → 上下文回滚一版，整图 FAILED
       └─ 超时未处理（默认 1800 秒）→ 按超时结束，整图 FAILED
```

### 11.2 审批人操作

1. 左侧导航进入 **系统管理 → 审批中心**；
2. 在待审批列表（按超时时间升序）中找到对应请求（含执行 ID、节点 ID、审批类型、超时时间）；
3. 点击 **批准** 或 **驳回**，可附审批意见；
4. 处理完成后，等待中的编排执行自动继续（或失败终止）。

### 11.3 注意事项

- 审批等待中的执行**不可被暂停 / 取消**（信号在节点边界才检查；审批自身的超时机制即为保护）；
- 同一审批请求只能处理一次，重复处理报「审批请求已处理」；
- 审批请求随编排图删除而级联清理。

---

## 12. 自主目标（Autonomous Goal）

**入口**：目前仅 Open API（无 Web 页面入口），SSE 流式返回。

### 12.1 提交目标

```bash
curl -N -X POST http://localhost:8081/api/v1/orchestration/goals \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "description": "为用户中心模块补充单元测试并确保全部通过",
    "type": "FEATURE",
    "maxIterations": 5
  }'
```

| 参数 | 必填 | 说明 |
|---|---|---|
| description | ✅ | 自然语言目标描述 |
| type | ❌ | 目标类型：FEATURE（默认）/ BUGFIX / REFACTOR / RESEARCH / OPS；影响推荐编排模式与预算配额 |
| maxIterations | ❌ | 自主循环（分解→执行→验收）最大轮数，默认 5 |

### 12.2 执行循环

```
goal_start
   → 目标分解（GoalPlanner）：LLM 规划器（需开启 agent.engine.planner.llm.enabled=true）
     把目标拆为带依赖关系的步骤列表；未开启时退化为单步执行
   → goal_decomposed（图 ID 与模式）
   → 循环{ 按波次并行执行计划 → 双闭环验收 + 置信度门控
        ├─ 通过 → goal_complete(SUCCESS)
        └─ 不通过 → reflection 事件，进入下一轮反思重规划 }
```

推荐模式映射：FEATURE / BUGFIX → PIPELINE，REFACTOR / OPS → SUPERVISOR，RESEARCH → SWARM。

### 12.3 防失控边界

引擎内置多重护栏，任一触发即以 FAILED 终态结束并记录原因：

| 护栏 | 默认值 |
|---|---|
| 最大迭代轮数 maxIterations | 5 |
| Token 预算 | 按任务层级（L1/L2/L3，默认 L2）配额，70% / 90% 发 budget_warning |
| 时间预算 | 默认 300 秒 |
| 同节点反思重做 | 最多 3 次 |
| 工具调用配额 | 默认 50 次 |

### 12.4 失败沉淀

自主目标失败时，失败案例（任务类型、失败点、根因、教训、规避规则）自动写入**失败案例库**（`failure_case` 表）与情景记忆，供后续同类任务检索规避；相似失败重复出现会累加发生次数。

---

## 13. 失败处理与容错

### 13.1 节点失败默认行为（fail-fast）

任一节点失败（AGENT 执行出错 / TOOL 调用失败 / HUMAN 审批驳回或超时 / ROUTER 无可命中出边等）→ 发出带节点 ID 的 error 事件 → **整图立即短路终止（FAILED）**，后续节点不再执行。引擎**不做节点级自动重试**。

### 13.2 失败传播开关（continueOnFailure）

画布级设置「失败传播」写入图变量 `continueOnFailure`：

| 取值 | 语义 |
|---|---|
| default（缺省） | fail-fast：任一节点失败整图立即终止 |
| true | best-effort：失败节点不产出、不发完成事件，**其余分支继续执行**（MERGE 收到空产出占位，非 MERGE 后继级联跳过并收到跳过提示）；**整图终态仍如实标记 FAILED** |
| false | 显式 fail-fast（与 default 等效，显式声明） |

> best-effort 适合"多路并行、部分成功即可"的场景（如多源调研）；需要严格事务语义的流程请保持默认。

### 13.3 节点级重试与超时（WFO-05）

AGENT / TOOL 节点的"重试与超时"折叠配置（属性面板）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `retryCount` | 0（不重试，保持现行为） | 上限 3。仅对**执行异常/超时**重试（AGENT 流式非阻塞退避重试、TOOL 同步重试）；error 事件（业务失败，如工具明确报错）不重试，避免重复副作用 |
| `retryBackoffMs` | 1000 | 重试退避间隔 |
| `timeoutSeconds` | 0（不启用） | 节点级执行超时上限 [1, 86400]；超时按失败处理并受失败传播语义约束（§13.2）；缺省由 LLM 客户端级 / 工具管线内部超时兜底 |

重试成功的节点，其重试次数随 node_complete 事件落库节点执行记录（retry_count），执行回放可见。

### 13.4 校验失败

图结构存在 ERROR 级问题时，保存与执行双闸均阻断，并返回形如「编排图结构校验未通过: VL-03: ...」的可读错误（规则见 §9）。

### 13.5 异常兜底

- SSE 流异常中断时，设计器把在途节点统一标红并展示错误；
- 节点级执行记录落库失败仅告警、不阻断执行本身；
- 取消 / 失败时执行记录保留变量终态快照与失败原因，支持事后审计（见 §15）。

---

## 14. API 参考

所有接口需携带 `Authorization: Bearer <token>`；统一响应包装 `{code, message, data}`，`code=10000` 为成功。下表路径前缀省略网关基地址（默认后端 `http://localhost:8081/api`）。

### 14.1 编排图管理

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/v1/orchestration/graphs` | 创建编排图（name、graphDefinition 必填；graphType、mode 可选，默认 AD_HOC / PIPELINE）；创建即 draft 状态 |
| PUT | `/v1/orchestration/graphs/{graphId}` | 更新定义；**仅 draft 可编辑**；保存前结构校验（ERROR 阻断） |
| GET | `/v1/orchestration/graphs` | 编排图列表（?status=draft|active 可选） |
| GET | `/v1/orchestration/graphs/{graphId}` | 图详情（错误码 18001=不存在） |
| PUT | `/v1/orchestration/graphs/{graphId}/activate` | 激活（draft → active，发布版本快照） |
| PUT | `/v1/orchestration/graphs/{graphId}/deactivate` | 下架（active → draft，执行与审批数据保留） |
| GET | `/v1/orchestration/graphs/{graphId}/versions` | 版本快照列表（按版本号倒序） |
| POST | `/v1/orchestration/graphs/{graphId}/versions/{versionId}/rollback` | 版本回滚（快照写回草稿，仅 draft 可回滚） |
| PUT | `/v1/orchestration/graphs/{graphId}/schedule` | 保存定时触发配置（cronExpr 必填；响应含预计算的 nextFireAt） |
| GET | `/v1/orchestration/graphs/{graphId}/schedule` | 查询定时触发配置 |
| PUT | `/v1/orchestration/graphs/{graphId}/webhook` | 保存 Webhook 配置（首次/regenerate 生成新 token，明文仅本次返回） |
| GET | `/v1/orchestration/graphs/{graphId}/webhook` | 查询 Webhook 配置（只含哈希） |
| POST | `/v1/orchestration/webhooks/{token}` | Webhook 匿名触发（body 原样作为输入；未命中/停用/关闭统一 404） |
| DELETE | `/v1/orchestration/graphs/{graphId}` | 删除（级联删除执行实例与审批请求） |
| GET | `/v1/orchestration/catalog/roles` | 角色目录（AGENT 节点 roleCode 数据源） |
| GET | `/v1/orchestration/catalog/tools` | 工具目录（TOOL 节点 toolName 数据源，代码 + 配置合并） |

### 14.2 执行管理

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/v1/orchestration/graphs/{graphId}/execute` | 同步执行（body: `{sessionId?, input?}`），阻塞返回执行记录（含 versionId / triggerType） |
| POST | `/v1/orchestration/graphs/{graphId}/stream` | 流式执行（SSE，`text/event-stream`） |
| GET | `/v1/orchestration/executions` | 执行实例列表（?graphId=、?status= 可选） |
| GET | `/v1/orchestration/executions/{executionId}` | 执行详情（18002=不存在） |
| GET | `/v1/orchestration/executions/{executionId}/nodes` | 执行的节点级记录（按时间升序，回放数据源） |
| POST | `/v1/orchestration/executions/{executionId}/pause` | 暂停（仅 RUNNING） |
| POST | `/v1/orchestration/executions/{executionId}/resume` | 恢复（仅 PAUSED）；返回 `resumable` 布尔 |
| POST | `/v1/orchestration/executions/{executionId}/resume/stream` | 断点续跑事件流（SSE，resumable=true 时调用，跳过已完成节点） |
| POST | `/v1/orchestration/executions/{executionId}/cancel` | 取消（RUNNING 优雅结束；PAUSED 丢弃检查点） |
| POST | `/v1/orchestration/goals` | 提交自主目标（SSE；body: `{description, type?, maxIterations?}`） |

### 14.3 HITL 审批

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v1/approvals/pending` | 待审批列表（按超时时间升序） |
| POST | `/v1/approvals/{requestId}/approve` | 批准（body: `{comment?}`） |
| POST | `/v1/approvals/{requestId}/reject` | 驳回（body: `{comment?}`） |

### 14.4 调用示例

```bash
# 创建编排图（Pipeline，两节点串行）
curl -X POST http://localhost:8081/api/v1/orchestration/graphs \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "开发-审查流水线",
    "mode": "PIPELINE",
    "graphType": "AD_HOC",
    "graphDefinition": {
      "nodes": [
        { "nodeId": "n1", "type": "AGENT", "roleCode": "DEVELOPER" },
        { "nodeId": "n2", "type": "AGENT", "roleCode": "CODE_REVIEW" }
      ],
      "edges": [ { "fromNode": "n1", "toNode": "n2" } ]
    }
  }'

# 流式执行（SSE）
curl -N -X POST http://localhost:8081/api/v1/orchestration/graphs/$GRAPH_ID/stream \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -d '{ "input": "实现一个用户登录接口" }'

# 暂停 → 恢复（断点续跑）
curl -X POST .../executions/$EXEC_ID/pause -H "Authorization: Bearer $TOKEN"
curl -X POST .../executions/$EXEC_ID/resume -H "Authorization: Bearer $TOKEN"   # 返回 resumable
curl -N -X POST .../executions/$EXEC_ID/resume/stream -H "Authorization: Bearer $TOKEN"
```

---

## 15. 数据持久化与可观测性

| 表 | 内容 |
|---|---|
| `orchestration_graph` | 编排图定义（含 graph_definition JSON 快照、orchestration_mode、status、version） |
| `orchestration_execution` | 执行实例：状态、当前节点、graph_snapshot（执行时的定义快照）、variables（含取消/失败时的终态快照）、final_output、error_message、iteration_count、token_used |
| `orchestration_node_execution` | 节点级执行记录：nodeId、类型、角色、状态、耗时、错误——执行回放数据源（`node_start` 建记录、`node_complete`/`error` 推进终态，幂等写入） |
| `approval_request` | HITL 审批请求：执行 ID、节点 ID、审批类型、payload、状态（pending/approved/rejected）、审批人与意见、超时时间 |
| `failure_case` | 失败案例库（自主目标失败沉淀，见 §12.4） |

可观测性：

- **分布式追踪**：每次编排执行开启 OTel 根 Span（`orchestration_execute`），自主目标每轮迭代包 Span；基于 Micrometer Tracing（OpenTelemetry Bridge）；
- **四环协同**：执行完成后自动触发评估 / 治理 / 审计环（失败图同样进入治理与审计）；
- **日志**：结构校验 WARNING、变量未解析告警、节点落库失败告警等均记录在服务日志。

---

## 16. 常见问题 FAQ

**Q1：保存时报「结构校验未通过（N 项错误）」？**
点击工具栏 **校验** 查看具体规则（红标 ERROR），点击问题条目可定位节点。高频问题：工具节点未选工具（VL-06）、图有环（VL-03）、ROUTER 出边没写条件（VL-03）。

**Q2：已激活的图想改怎么办？**
列表卡片点「下架」回到草稿即可重新编辑（执行与审批历史完整保留）；改完重新激活会产生新版本快照（v2、v3…）。历史版本可在「版本历史」弹窗中查看、与当前草稿对比、或一键回滚。**不再需要删图重建。**

**Q3：`${var.xxx}` 历史写法还能用吗？**
能用：引擎查不到字面变量 `var.xxx` 时会自动去前缀按 `${xxx}` 降级解析（记告警日志）。新图请统一写 `${input}`、`${节点ID}`；VL-09 校验对两种写法同源生效。

**Q4：ROUTER 节点执行报「无可命中出边」？**
所有出边条件都未命中且没有无条件 / else 兜底边。为 ROUTER 的分支补一条 `else` 兜底边即可。

**Q5：SWARM / SUPERVISOR 模式下连线怎么不生效？**
这两种模式按设计**忽略边结构**（SWARM 由 Agent 输出的 `HANDOFF` / `FINISH` 指令驱动，SUPERVISOR 按节点声明顺序串行）。连线仅作视觉参考，画布顶部有金色提示条。需要按连线执行请使用 PIPELINE。

**Q6：暂停后点「恢复」，执行会继续跑吗？**
会。界面已闭环：点「恢复」后自动完成状态恢复并订阅断点续跑事件流，事件实时滚动到执行事件面板直至终态。检查点已持久化（内存+DB 双写），**服务重启后恢复依然可用**；仅持久层也不可用时才提示重新执行。

**Q7：执行历史里某条记录打开回放没有节点数据？**
该执行产生于节点级落库功能上线之前，没有节点记录。新执行均有。

**Q8：Agent 节点没有绑定 Agent 实例也能跑吗？**
能。未指定 refId 时以图变量 `modelProvider` / `modelName` 兜底解析模型执行；两者都没有时该节点才会失败。

**Q9：HUMAN 节点等审批时，暂停 / 取消按钮没反应？**
审批等待中的执行不响应暂停 / 取消信号（信号在节点边界检查）。等待审批自身的超时（默认 1800 秒）即为保护机制；可在审批中心驳回以终止流程。

**Q10：画布节点上的"监督者"徽标是什么？**
SUPERVISOR 模式下第一个 AGENT 节点即监督者，徽标实时跟随模式与节点顺序变化；切换到其他模式徽标自动消失。

**Q11：执行输入（input）会覆盖图变量吗？**
会。构建上下文时图定义 variables 先入，`input` 后入覆盖同名键；`input` 本身固定写入 `input` 键供 `${input}` 引用。

**Q12：计划节点（PLAN）拆出来的步骤在哪里看？**
执行事件流中的 `plan_created` / `plan_updated` 事件携带计划步骤列表（含步骤文本与状态），运行预览控制台可见。

---

## 17. 典型场景示例

以下场景均可用创建弹窗中的内置模板一键起步（模板清单见附录 C），进入设计器后按需调整 Agent / 角色 / 工具配置。

### 17.1 审批发布链（模板：审批发布链）

需求梳理 → **人工审批** → 开发实现 → 提交工具。运行后在审批中心批准，流程继续；驳回则整图终止。适合需要人工把关的交付流程。

### 17.2 条件路由（模板：条件路由（审查通过/驳回））

开发产出交审查 Agent 评估，ROUTER 按审查输出路由：通过 → 汇合输出；驳回 → 进入修复分支后汇合。要点：审查 Agent 的 roleCode 决定输出措辞，条件表达式需与其输出匹配（如 `var:n2 contains '通过'`），并保留 `else` 兜底。

### 17.3 并行扇出与汇聚（模板：并行扇出与汇聚）

一份需求并行交给开发与测试两个视角处理，MERGE 汇聚后输出综合结论。可在 MERGE 上把合并策略改为 `json_merge` 以获得结构化汇总。

### 17.4 调研接力（模板：调研接力（群体协作））

三个 Agent 按声明顺序起步，运行时各 Agent 在输出中写 `HANDOFF:节点ID|原因` 移交控制权、写 `FINISH` 结束；接力超过 8 次强制终止。适合边界动态变化的调研任务。

### 17.5 监督分派（模板：监督分派（监督者模式））

首个 AGENT 为监督者统筹，其余专家按声明顺序依次接手，产出累积传递。适合有明确分工、需要中央协调的重构 / 运维类任务。

### 17.6 辩论共识（模板：辩论共识（Debate））

两个 Agent 并行给出方案，MERGE 节点作裁判综合裁决选出最优。适合架构选型、方案评审。

---

## 附录 A：节点类型速查表

| 类型 | 名称 | 状态 | 核心属性 | 一句话用途 |
|---|---|---|---|---|
| AGENT | Agent 节点 | ✅ | refId、roleCode、inputs、outputSchema、retryCount/retryBackoffMs/timeoutSeconds | 委派给智能体执行 LLM 任务 |
| TOOL | 工具节点 | ✅ | toolName（必填）、arguments、outputVar、retryCount/retryBackoffMs/timeoutSeconds | 直接调用工具，无推理 |
| HUMAN | 人工审批 | ✅ | timeoutSeconds（默认 1800）、assigneeId/assigneeRole（审批人圈定） | 阻塞等人工批准 / 驳回 |
| ROUTER | 条件路由 | ✅ | 出边 condition | 按条件选分支 |
| PARALLEL | 并行扇出 | ✅ | —（出边 ≥2） | 同时触发全部分支 |
| MERGE | 汇聚合并 | ✅ | strategy（默认拼接 / json_merge） | 等全部分支到齐后合并 |
| PLAN | 动态规划 | ✅ | goalType（默认 FEATURE） | 输入经规划器拆解为波次并行子计划 |
| SUBGRAPH | 嵌套子图 | ✅ | refId（必填，指向已激活图） | 以沙箱上下文执行子图，深度≤2（需服务端开关） |

## 附录 B：内置角色清单（RoleCode）

角色目录 = 内置 12 个 SDLC 角色（同名可被外部 SPI 配置覆盖）+ 外部角色扩展，接口 `GET /v1/orchestration/catalog/roles`：

| roleCode | 角色名 | SDLC 阶段 |
|---|---|---|
| REQUIREMENT_PM | 需求PM | REQUIREMENT |
| ARCHITECT | 架构师 | DESIGN |
| DATABASE_DESIGN | 数据库设计 | DESIGN |
| DEVELOPER | 开发者 | DEVELOP |
| REFACTOR | 重构工程师 | DEVELOP |
| CODE_REVIEW | 代码审查 | REVIEW |
| SECURITY_AUDIT | 安全审计 | REVIEW |
| TEST_ENGINEER | 测试工程师 | TEST |
| QA | QA工程师 | TEST |
| DEVOPS | DevOps工程师 | DEPLOY |
| SRE | SRE工程师 | OPS |
| DOC_ENGINEER | 文档工程师 | OPS |

## 附录 C：创建模板清单

| 模板 ID | 名称 | 模式 | 说明 |
|---|---|---|---|
| （blank） | 空白图 | — | 推荐：创建后进设计器拖拽编排 |
| approval-release | 审批发布链（含人工审批） | PIPELINE | 需求梳理 → 人工审批 → 开发实现 → 提交工具 |
| review-router | 条件路由（审查通过/驳回） | PIPELINE | 审查评估后按输出变量路由，驳回走修复分支 |
| parallel-merge | 并行扇出与汇聚 | PIPELINE | 开发与测试双视角并行，MERGE 汇聚综合结论 |
| research-swarm | 调研接力（群体协作） | SWARM | 三 Agent 指令接力，边不参与执行 |
| supervisor-dispatch | 监督分派（监督者模式） | SUPERVISOR | 首个 AGENT 为监督者，专家按序接手 |
| debate-consensus | 辩论共识（Debate） | DEBATE | 双方案并行辩论，MERGE 裁判裁决 |

## 附录 D：相关配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `agent.engine.planner.llm.enabled` | false | 开启 LLM 目标规划器（自主目标与 PLAN 节点的智能拆解）；关闭时单步兜底 |
| LLM 规划器 maxSteps | 8 | 计划步骤数上限 |
| SWARM maxHandoffs | 8 | 接力次数上限（超限强制终止） |
| HUMAN timeoutSeconds | 1800 | 审批默认超时（可在节点配置覆盖，上限 86400） |
| 自主目标 maxIterations | 5 | 分解-执行-验收循环上限 |
| 自主目标时间预算 | 300000 ms | 单次自主目标时间预算 |
| 自主目标工具调用配额 | 50 次 | AntiRunawayGuard 工具调用上限 |
| `agent.engine.orchestration.subgraph.enabled` | false | SUBGRAPH 嵌套子图特性开关（关闭时按 AGENT 执行） |
| `agent.engine.webhook.enabled` | false | Webhook 触发总开关（开启前须完成安全评审；关闭时匿名端点 404） |
| `agent.engine.tool.orchestration.timeout-seconds` | 300 | Agent 工具 run_orchestration_graph 单次执行时长帽（秒） |

---

*本手册基于代码实现编写（编排引擎 `gewu-agent-engine/orchestration`、应用服务 `gewu-application/orchestration`、接口 `OrchestrationController` / `ApprovalController`、前端 `OrchestrationPage` / `OrchestrationDesigner` 及 designer 组件族）。如功能迭代，请以最新代码为准并同步更新本手册。*
