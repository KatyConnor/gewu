# 智能体与技能接入 AI 交互流程设计

> 文档编号：35 | 日期：2026-08-02 | 模块：Agent / Skill / 对话链路
> 目标：让"智能体管理"与"技能"从孤立 CRUD 真正串联进 AI 对话，使"选智能体对话、技能增强能力"可用

---

## 一、背景与目标

### 1.1 现状问题
当前平台已具备智能体(Agent) CRUD、技能(Skill) CRUD、流式对话(ChatPage)三套独立功能，但三者**互不串联**：

- **对话不经过智能体**：`ChatPage` 发起 `chatStream` 时从不传 `agentId`，后端 `AgentExecutionEngine` 以 `agent=null` 运行，沦为无 systemPrompt、无工具的通用 LLM 对话。
- **`session.agent` 写而不读**：建会话时存的 agent 字段，对话执行链路从不读取，是"死元数据"。
- **技能完全孤立**：`SkillController`/`SkillService` 是自包含 CRUD，执行链路（`AgentExecutionEngine`/`AgentMessageBuilder`/`ToolExecutionService`）零引用 Skill，且 Agent 与 Skill 之间无任何关联模型。
- **页面无串联入口**：`MyAgentsPage`/`AgentManagePage`/`AgentMarketPage` 没有"与该智能体对话"入口；`AgentManagePage` 的"配置/对话次数"是占位。

### 1.2 设计目标
1. **智能体可对话**：用户在对话页可选择智能体，对话按智能体的人设(systemPrompt)+工具执行。
2. **技能可赋能**：技能作为智能体的可复用"能力指令包"，挂载到智能体后注入对话，增强智能体的领域能力。
3. **会话绑定持久化**：会话与智能体绑定，后续对话自动沿用，无需重复选择。
4. **闭环可观测**：智能体的"对话次数"真实反映使用情况。

---

## 二、同业产品调研

### 2.1 主流 Agent 产品架构对比

| 产品 | 智能体组成 | 技能/工具定位 | 对话执行机制 |
|------|-----------|--------------|-------------|
| **扣子 Coze** | 人设(系统提示词)+技能(插件/工作流)+知识库+变量+数据库+触发器 | 插件=单一功能外部API工具；工作流=多步骤编排；均作为"技能"挂载 | 系统提示词作"大脑"，按意图调度插件/工作流，结合知识库生成回复 |
| **Dify** | LLM+Tools(内置/自定义API/工作流)+Knowledge+Memory | Tool=Function Calling 工具；Workflow 独立编排可被 Agent 调用 | ReAct / Function Calling 两种推理模式，工具循环 |
| **OpenAI GPTs** | Instructions(系统提示词)+Actions(OpenAPI)+Knowledge(文件)+Capabilities(代码/联网/DALL-E) | Actions=外部API；Knowledge=RAG 文档 | Instructions 协调，按需调用 Actions/能力 |
| **本平台(现状)** | systemPrompt+modelConfig+工具(Tool) | Skill=独立CRUD(未接入) | 引擎已支持 systemPrompt+工具循环，但前端不传 agentId |

### 2.2 关键借鉴
- **Coze 的"技能"双层模型**：插件(工具型) + 工作流(编排型)。本平台已有 `agent_tool`(工具型) 和 `workflow`(编排型)，对应 Coze 插件/工作流。
- **缺口**：Coze 的"技能"还包含"能力指令/知识片段"（通过系统提示词增强），本平台缺这一层——这正是 `Skill` 应补的定位。
- **统一心智**：`Agent = 人设 + 工具(Tool) + 技能(Skill能力指令) + 模型`，对话时注入。

---

## 三、设计方案

### 3.1 概念模型重新定义

```
Agent（智能体）
├── 人设：systemPrompt（角色/行为约束）
├── 模型：modelProvider + modelName + modelConfig
├── 工具(Tool)：外部API/MCP/沙箱代码执行（已有 agent_tool，工具型能力）
├── 技能(Skill)：可复用能力指令包（提示词级能力增强，本次新增）
└── 状态：status(1启用/0禁用)
```

**Skill 的定位（关键决策）**：
- Skill ≠ Tool。Tool 是"外部能力调用"（API/MCP/代码），已有 `agent_tool` 体系承载。
- Skill = "可复用的能力指令/知识片段"，其 `content` 是提示词文本，注入智能体的 system prompt，赋予智能体特定领域的行事方法、知识背景或行为范式。
- 类比：Coze 的"技能"中提示词增强部分 + 轻量知识库片段；GPTs 的 Instructions 增量。
- 价值：技能可跨智能体复用（如"代码评审技能""需求拆解技能"可挂到多个智能体），避免每个智能体重写相同提示词。

### 3.2 串联交互流程

```
用户选择智能体(可挂载多个技能)
   │
   ├─ 创建会话(session.agent = agentId，持久化绑定)
   │
   ▼
发起对话(chatStream 携带 agentId，或后端回退读 session.agent)
   │
   ▼
AgentExecutionEngine.executeAgentStream
   ├─ loadAgent(agentId) → 获取 systemPrompt + 模型配置
   ├─ AgentMessageBuilder.buildMessages
   │    ├─ 注入 agent.systemPrompt（人设）
   │    ├─ 注入关联 Skills 的 content（能力指令）★新增
   │    ├─ 注入 agentMode/thinkingStyle 指令
   │    └─ 注入历史上下文 + 当前消息
   ├─ buildToolDefinitions → 加载 agent_tool（工具能力）
   └─ LLM 流式推理 + 工具循环（已有，无需改造）
```

### 3.3 数据模型

**新增 `agent_skill` 关联表（V11 迁移）**：
```sql
CREATE TABLE agent_skill (
  id VARCHAR(26) NOT NULL,
  agent_id VARCHAR(26) NOT NULL,
  skill_id VARCHAR(26) NOT NULL,
  sort_order INT DEFAULT 0,
  deleted TINYINT DEFAULT 0,
  created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
  created_by VARCHAR(26), updated_by VARCHAR(26),
  PRIMARY KEY(id),
  UNIQUE KEY uk_agent_skill (agent_id, skill_id),
  KEY idx_agent_skill_agent (agent_id)
);
```
- 多对多：一个 Agent 可挂多个 Skill，一个 Skill 可被多 Agent 复用。
- `sort_order` 控制注入顺序。

**`session.agent` 语义统一**：从"Agent 名称"改为"Agent ID"（当前代码已存 ID，仅注释误导，修正注释即可）。

### 3.4 API 设计

| 接口 | 说明 | 状态 |
|------|------|------|
| `GET /api/v1/agents/{agentId}/skills` | 获取智能体已挂载的技能 | 新增 |
| `POST /api/v1/agents/{agentId}/skills/{skillId}` | 挂载技能到智能体 | 新增 |
| `DELETE /api/v1/agents/{agentId}/skills/{skillId}` | 卸载技能 | 新增 |
| `POST /api/v1/ai/chat/stream` | 对话(传 agentId 或回退 session.agent) | 改造 |
| `POST /api/v1/sessions` | 建会话绑定 agent | 已支持(agent 字段) |

### 3.5 前端交互设计

**ChatPage 智能体选择器**：
- 在对话页顶部工具栏新增"智能体"下拉（与模型/模式并列），选项为"通用助手(无)"+"我的智能体"列表。
- 选中智能体后：新建会话绑定该 agent；后续消息携带 agentId。
- 选中智能体时展示其名称+已挂载技能数（如"代码助手 · 3技能"）。

**智能体卡片"对话"入口**：
- `MyAgentsPage`/`AgentManagePage` 智能体卡片新增"对话"按钮 → 跳转 `chat` 页并预选该智能体。
- 通过 Redux store 传递 `pendingAgentId`，ChatPage 读取后自动选中。

**智能体技能配置**：
- `AgentManagePage` 编辑弹窗新增"技能"多选区（从技能库选择挂载）。
- 详情抽屉展示已挂载技能列表。

---

## 四、用户故事与验收标准

### US-1：选择智能体对话
**作为**已登录用户，**我希望**在对话页选择一个智能体后与之对话，**以便**获得具有特定人设和能力的 AI 协助。

- **Given** 我已创建智能体"代码评审助手"(systemPrompt="你是严谨的代码评审专家...")
- **When** 我在对话页选择该智能体并发送"帮我评审这段代码"
- **Then** AI 以代码评审专家的口吻回复（systemPrompt 生效）
- **And** 会话绑定该智能体，后续对话无需重选

### US-2：技能增强智能体能力
**作为**用户，**我希望**给智能体挂载"代码评审技能"后，智能体按技能中定义的评审 checklist 执行评审，**以便**复用标准化的能力。

- **Given** 技能"代码评审技能"(content="评审时检查：1.命名规范 2.边界条件 3.异常处理...")
- **When** 我将该技能挂载到"代码评审助手"智能体并发起对话
- **Then** AI 的评审回复遵循技能中的 checklist（skill content 注入生效）

### US-3：从智能体管理进入对话
**作为**用户，**我希望**在"我的智能体"页面点击某智能体的"对话"按钮直接开始对话，**以便**快速使用。

- **Given** 我在"我的智能体"页面
- **When** 点击"代码评审助手"卡片的"对话"按钮
- **Then** 跳转到对话页，该智能体已自动选中，可立即开始对话

### US-4：对话次数真实统计
- **Given** 智能体"代码评审助手"已有 5 次对话
- **When** 我在智能体管理页查看
- **Then** "对话次数"显示 5（基于 session 表按 agent 统计，真实反映使用）

---

## 五、技术实现要点

### 5.1 后端

**A. agent_skill 关联（新增）**
- `AgentSkill` Domain（`gewu-domain/.../agent/AgentSkill.java`）+ `AgentSkillMapper`
- `AgentService` 新增 `listAgentSkills(agentId)`/`mountSkill(agentId, skillId)`/`unmountSkill(agentId, skillId)`
- `AgentController` 新增 3 个端点

**B. Skill 注入对话（核心改造）**
- `AgentExecutionEngine` 注入 `SkillMapper`（或 `AgentSkillMapper`+`SkillMapper`）
- `AgentMessageBuilder.buildMessages`：agent != null 时，加载关联 skills（按 sort_order），将 skill.content 拼入 system 消息（agent.systemPrompt 之后）：
  ```
  [agent.systemPrompt]
  
  # 已挂载技能
  ## 技能：{skill.skillName}
  {skill.content}
  ...
  ```

**C. session.agent 回退（AiChatController 改造）**
- `chatViaLegacy`/`chatStreamViaLegacy`：当 `request.getAgentId()` 为空且 `sessionId != null` 时，读 `session.agent` 作为 agentId（补一次 session 查询）。

**D. 对话次数统计优化**
- `AgentService.countConversations` 改为按 `session.agent = agentId` 统计（比 agent_execution 更准确，因对话经 session）。

### 5.2 前端

**A. ChatPage 智能体选择器**
- 新增 `agentId` state + 智能体下拉（调 `listAgents` 取"我的智能体"）
- `chatStream` 请求体传 `agentId`
- `startNewChat` 时 `createSession({ agent: agentId })`

**B. 智能体"对话"入口**
- `MyAgentsPage`/`AgentManagePage` 卡片加"对话"按钮 → `dispatch(setPage('chat'))` + 传 `pendingAgentId`
- store 增加 `pendingAgentId`，ChatPage 读取后自动选中并清空

**C. 技能挂载 UI**
- `AgentManagePage` 编辑弹窗加技能多选（调 `listSkillLibrary` + `listAgentSkills`）
- `agent.ts` 新增 `listAgentSkills`/`mountSkill`/`unmountSkill`

---

## 六、优先级与里程碑

| 优先级 | 内容 | 价值 |
|--------|------|------|
| **P0** | Agent 接入对话(前端选择器+后端 session.agent 回退) | 让智能体真正可用 |
| **P0** | 智能体"对话"入口(卡片按钮+跳转) | 闭环体验 |
| **P1** | agent_skill 关联 + Skill 注入对话 | 技能能力化 |
| **P1** | 技能挂载 UI(编辑弹窗多选) | 技能管理闭环 |
| **P2** | 对话次数按 session 统计优化 | 数据准确性 |
| **P2** | session.agent 语义注释修正 | 代码整洁 |

---

## 七、不包含（Out of Scope）

- 工作流(Workflow)接入对话：Workflow 已有独立编排模块，与 Agent 的集成（Agent 调用 Workflow 作为技能）属更大范围，本期不做。
- 知识库(Knowledge/RAG)：Coze/Dify 的知识库能力，本平台暂无向量检索基础设施，不在本期。
- 多智能体协作(Multi-Agent)：`MultiAgentCoordinator` 属休眠的 wenshi 路径，本期不启用。
- 技能市场(Skill Market)：技能库已全量可见，暂不做独立市场。
