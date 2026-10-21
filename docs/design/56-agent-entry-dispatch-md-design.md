# 56 · 格物智能体入口调度文件（AGENTS.md / GEWU.md）设计

| 项 | 内容 |
|---|---|
| 状态 | 草案 v1.2.1（RPT-003 有条件通过两项放行条件已整改：一键同步限同名+覆盖前快照；版本裁决收窄为双方 opt-in 协议，默认回归主流就近语义） |
| 类型 | 新功能设计（平台端 + 桌面端） |
| 作者 | Agent（SDLC 架构设计） |
| 日期 | 2026-10-19 |
| 关联 | docs/design/42-gewu-desktop-architecture-design.md（§7.4 project.context 目录约定）、docs/analysis/workspace-design-analysis.md（用户工作空间模型） |

---

## 1. 背景与动机

主流智能体引擎（ZCode、Claude Code、OpenCode、Codex、Cursor 等）都有一个**入口调度文件**：一段随会话自动载入模型上下文的 Markdown 指令，用于声明项目约定、编码规范、技能索引、协作流程等。它是"规范/标准/技能进入模型上下文"的总入口，也是生态互认的事实标准（AGENTS.md 已被 60k+ 开源项目与 20+ 工具采用）。

格物引擎当前**没有这个设计**：平台端提示词全部来自 DB 字段与硬编码（`SystemPromptComposer` 只拼 agent.systemPrompt + 模式/思维指令），没有任何"从文件加载指令"的机制；桌面端仅有 `project.context` 工具按需读取 AGENTS.md（作为工具结果，非自动注入）。用户无法用一份通用文件向格物智能体声明"这个项目/这个用户该怎么干活"。

本设计补齐该能力，并按格物双形态分开设计：

- **平台端**：入口文件与**用户绑定**——用户根目录空间（用户工作空间卷根）为全局级配置，项目根目录为项目级配置；
- **桌面端**：以 `~/.gewu/` 目录为全局配置根，项目本地目录根为项目级配置。

## 2. 目标与非目标

**目标**
1. 支持读取 `AGENTS.md`（生态兼容）与自定义 `GEWU.md`（格物专属）作为入口调度文件；
2. 平台端：用户全局级 + 项目级两层，**默认项目级优先**（主流就近语义，导入外部 AGENTS.md 零修改生效）；**版本裁决仅在两侧都声明版本时生效**（opt-in 协议，版本最高者胜）；全局升版对未参与协议的项目文件走**显式升级通知**，不静默覆盖；
3. 桌面端：`~/.gewu/` 全局级 + 项目目录根项目级，同一套裁决规则；
4. 入口内容在会话启动时自动注入 system prompt，子代理继承；
5. 全程可审计：每会话记录"选了哪个文件、什么版本、为什么"。

**非目标（本期不做，见 §12 开放问题）**
- 子目录嵌套发现（monorepo 就近匹配）；
- 多文件拼接/叠加模式（业界 Claude Code/ZCode/OpenCode 的做法，格物采用单选语义）；
- `@path` import 语法、CLAUDE.md/GEMINI.md 别名兼容；
- Wenshi 旁路（推理引擎/多智能体协调器）硬编码提示词的改造。

## 3. 业界调研

| 引擎 | 文件名 | 层级 | 合并语义 | 注入点 | 版本/冲突裁决 |
|---|---|---|---|---|---|
| **AGENTS.md 官方规范**（Codex/Jules/gemini-cli/opencode/Cursor/Devin 等 20+ 工具采用） | `AGENTS.md` | 仓库根 + 任意子目录 | **就近单选**："nearest file wins"，显式聊天指令最高 | 会话自动读取 | 无版本机制，无必填 schema |
| **Claude Code** | `CLAUDE.md` | 企业策略 > 项目根（+CLAUDE.local.md）> 用户 `~/.claude/CLAUDE.md`；子目录文件进入子树工作时按需加载 | **多文件拼接**（企业+项目+用户各一段） | 会话启动注入 system prompt；`#` 快捷追加、`/memory` 编辑；支持 `@path` import | 无版本机制；层级即优先级 |
| **ZCode** | `AGENTS.md` | 用户默认 `~/.zcode/AGENTS.md` + 工作区 `AGENTS.md`，另支持多平台全局槽位（`~/.claude/CLAUDE.md`、`~/.config/opencode/AGENTS.md`、`~/.agents/AGENTS.md`） | **多文件拼接**（各段带来源标注） | 会话启动注入 | 层级就近覆盖；项目层只能比全局层更严（单向棘轮） |
| **OpenCode** | `AGENTS.md` + 配置 `instructions` | 全局 `~/.config/opencode/AGENTS.md` + 项目 | 拼接 | 会话启动注入 | 无版本机制 |
| **Cursor** | `.cursor/rules/*.mdc`（旧 `.cursorrules`） | 项目级规则文件组 | 按 frontmatter（globs/alwaysApply）选择性拼接 | 会话注入 | 无版本机制 |
| **Gemini CLI** | `GEMINI.md`（contextFileName 可配） | 全局 `~/.gemini/` + 项目 + 子目录 | 拼接 | 会话注入 | 无版本机制 |

**要点提炼**
1. "存在一个入口 md"已是行业共识，AGENTS.md 是互认标准，任何引擎都不应自造孤立格式；
2. 合并语义分裂为两派：**拼接派**（Claude Code/ZCode/OpenCode/Gemini，全局+项目都进上下文）与**单选派**（AGENTS.md 规范：nearest wins）；
3. **没有任何主流引擎做版本裁决**——格物"**两侧都声明版本时**以版本最高为准"是自定义需求（opt-in 版本协议），需要自定版本头格式与确定性算法；
4. 管理配套大同小异：初始化命令/模板、内存编辑入口、会话内刷新。

## 4. 格物现状与落点（调研证据）

### 4.1 平台端

| 事实 | 证据 |
|---|---|
| system prompt 唯一收口：`SystemPromptComposer.compose()` = agent.systemPrompt + 模式指令 + 思维指令 | gewu-agent-engine/.../message/SystemPromptComposer.java:20-44；DefaultMessageBuilder.java:23-40；ReactAgentExecutor.java:1922-1931 |
| 存在"向 system 消息追加段落"的成熟先例（记忆路由 SPI） | gewu-application/.../adapter/WenshiMemoryRouterAdapter.java:69-85（`"\n\n## 相关记忆"` 追加式） |
| 服务端引擎**零** AGENTS.md/GEWU.md 加载逻辑（全目录 grep 无命中） | gewu-agent-engine、gewu-application、gewu-interface |
| 用户全局空间 = 每用户一行 workspace + 一个 Docker 卷挂 `/workspace`，容器即焚、卷持久 | gewu-sandbox/.../DockerSandboxProvider.java:84-95；docs/analysis/workspace-design-analysis.md:151-152 |
| 卷与 MinIO **双向自动同步**，md 在白名单内；`projects/**` 与 `.git` 排除（代码走 Git） | gewu-application/.../workspace/WorkspaceSyncService.java:47-49,71,181-194,211-268 |
| 项目根 = 卷内 `projects/{projectId}/repo`（创建时固化）；沙箱内绝对路径 `/workspace/` 前缀拼接 | ProjectService.java:78；ProjectRepoService.java:25,46-48 |
| 引擎写文件统一走 `SandboxClient.writeFile`（docker cp tar 流，仅限 /workspace 内，自动 mkdir、触发 V2M 同步） | SandboxClient.java:157-160；ContainerFileService.java:39-70,173-183；SessionFileWorkspaceService.java:84-86 |
| 项目创建无脚手架钩子（纯 DB 事务）；最接近的逐文件初始化先例是 Git 凭证注入 | ProjectService.java:54-91；DevWorkspaceService.java:620-655 |
| 会话根路由：有项目会话 directory=`projects/{id}/repo`，无项目会话=卷根；文件工具相对会话根、拒绝对路径与 `..` | SessionService.java:89-92；SessionFileWorkspaceService.java:53-54,560-573 |
| 子代理共享父会话工作空间 | ReactAgentExecutor.java:1404 |

**落点结论**：用户"根目录空间"映射为**用户工作空间卷根 `/workspace`**（MinIO 侧镜像 `workspaces/{userId}/`），这是平台内唯一"每用户持久、跨会话一致"的目录，与"我的文件"页天然打通；项目级映射为**项目仓库根**。全局文件以 MinIO 对象为权威源（不依赖沙箱存活），项目文件以沙箱卷（即 Git 仓库内容）为源。

### 4.2 桌面端

| 事实 | 证据 |
|---|---|
| `~/.gewu` 已是事实配置根：launch.json、data/、workspace/、skills/、agents/、logs/ 六类先例，一处集中构建 | gewu-desktop/electron/main.js:548-615 |
| 侧车 JVM 内嵌同一引擎；AgentTask 在 DesktopChatController 构建后 `agentEngine.executeStream(task)` | gewu-desktop-runtime/.../DesktopChatController.java:151-168 |
| `project.context` 工具已有 AGENTS.md → rules/*.md → ~/.gewu/skills 与 .gewu/skills 的装载链，16KB 上限，但**只作为工具结果、不进 system prompt** | gewu-desktop-runtime/.../tools/ProjectContextTool.java:16-27,49,53,64-82 |
| 项目 = 本地目录（local_project.directory），根解析：显式 workspaceRoot > 会话 directory > 全局 `gewu.desktop.workspace-root`（默认 ~/.gewu/workspace） | db/migration/V5__local_project.sql:1-12；LocalFileService.java:47-69 |
| 桌面 system prompt 同走 SystemPromptComposer，无 AGENTS.md 自动注入 | SystemPromptComposer.java:20-44 |

**落点结论**：桌面端全局级放 `~/.gewu/AGENTS.md|GEWU.md`（沿用既有 gewuRoot 约定），项目级放 `local_project.directory` 根；需要新增统一 Resolver 并让注入与 `project.context` 复用，消除"系统提示词与工具读到不同文件"的分叉。

## 5. 总体设计

### 5.1 文件规范

**命名与同目录发现顺序**：每个层级目录内按 `GEWU.md` → `AGENTS.md` 顺序探测，**命中即停**。GEWU.md 是格物专属定制（可写格物特有语义），AGENTS.md 是生态兼容兜底（同一份文件可被 Codex/Cursor 等工具共用）。固定顺序不做配置，降低理解成本。

**层级与路径**：

| 端 | 层级 | 路径（容器内/本机） | 权威存储 |
|---|---|---|---|
| 平台 | 用户全局 | `/workspace/GEWU.md`（或 AGENTS.md） | MinIO `workspaces/{userId}/GEWU.md`（卷为live副本，双向同步） |
| 平台 | 项目级 | `/workspace/projects/{projectId}/repo/GEWU.md` | 沙箱卷（主来源：Git 仓库自带） |
| 桌面 | 用户全局 | `~/.gewu/GEWU.md` | 本地文件系统 |
| 桌面 | 项目级 | `{projectDirectory}/GEWU.md` | 本地文件系统 |

**版本头**（裁决依据，两种写法等价，frontmatter 优先）：

```markdown
---
description: 项目协作规范（格物智能体调度入口）
version: 1.3.0
---

<!-- gewu-entry: v1.3.0 -->   ← 无 frontmatter 时的兼容写法
```

解析规则：frontmatter `version:` 取 semver（`主.次.修`，允许 1~3 段）；否则扫描文件头部（前 20 行）`gewu-entry` 注释标记；都没有则版本记为**无（null，不参与跨层版本比较，见 §5.2 规则 2b）**。解析失败的版本声明视为无版本，不报错。**归一化**（评审 F-306）：缺段补 0（`1.3` ≡ `1.3.0`）后按 semver 比较；`v` 前缀容忍剥离。

**版本头的语义归属（评审质询修订·对齐业界扩展模式）**：版本协议是格物自有语义（业界零先例），按"自有文件承载自有语义、标准文件保持原味"的业界惯例收窄——版本头**主要面向 GEWU.md 与平台治理模板**；AGENTS.md 中的版本头**宽容识别**（可解析、可在"双方都带版本"时参与裁决），但**无版本头的 AGENTS.md 永远不会被带版本的全局文件静默覆盖**。从开源仓库导入标准 AGENTS.md **零修改即生效**（§5.2 规则 2b）。**同层级（同目录）内版本不参与裁决**——发现顺序固定为 GEWU.md → AGENTS.md 命中即停：这是有意简化，理由是同目录双文件属"用户同时服务多工具"场景，GEWU.md 的存在本身即表达了格物专属意图，若再引入同层版本比较会让"格物优先"语义被版本号意外翻转。

**内容约定**（对齐 agents.md 推荐结构，纯 Markdown 无强制 schema）：项目概述、构建/测试命令、代码风格、规范与技能索引、安全红线、协作流程。编码 UTF-8。大小上限统一按**字节**口径（既有 `project.context` 工具的 `TOTAL_CAP=16_000` 为字符口径，中文场景约合 48KB UTF-8，本设计不沿用该值）：单文件读取上限 64KB，注入总量上限 32KB（可配 `gewu.entry.max-bytes`），超限截断并在段尾追加截断说明行。

### 5.2 层级选择算法（默认主流就近 + 双方 opt-in 版本裁决）

纯函数实现，放 gewu-common（`EntryFileSelector`），两端复用：

```
输入：candidates = [全局(路径, 版本), 项目级(路径, 版本)]   # 每层已按 GEWU.md > AGENTS.md 探测完
输出：chosen + 决策原因

规则（按序判定）：
0. 双方都不存在 → chosen=none（reason=no-entry），不追加注入段        ← 评审 F-305 补规格
1. 仅一个存在 → 选它（reason=only-candidate）
2. 两者都有：
   a. 双方都声明版本且不等 → **版本最高者胜**（reason=version-higher）   ← 格物 opt-in 版本协议
   b. 任一侧无版本，或双方版本相等 → **项目级胜**（reason=project-default） ← 主流就近语义兜底
```

设计说明（v1.2 评审质询修订）：
- **默认语义回归主流**：项目级文件无论有无版本头都优先于全局——与 agents.md 规范"nearest wins、无必填 schema"一致，与 Claude Code/ZCode/OpenCode"项目文件总是生效"的用户预期一致；从外部导入标准 AGENTS.md 零修改即生效。
- **版本裁决收窄为双方 opt-in 协议**：仅当两侧都声明版本才比较高低（典型：GEWU.md 对 GEWU.md、治理模板对治理模板）。这是格物与主流的关键差异点，但被"自有文件承载自有语义"的笼子约束——不再改变标准 AGENTS.md 的行为含义。项目想锁定自持版本，把版本号抬到更高即可。
- **升级感知走显式通知，不做静默覆盖**：全局模板升版后，对未参与版本协议（无版本头）或版本较低的项目文件，平台在项目调度文件页签展示升级提示（查看差异/一键同步，§6.4）——把语义惊喜变成用户显式动作，替代 v1.1 的静默覆盖。
- 与 STD-HUB"单向棘轮"（项目只能更严）不同：棘轮管内容方向，版本裁决管代际新旧，二者不冲突也不混用。
- 决策三元组 `(chosenSource, chosenVersion, reason)` 必须落审计日志（§9）。

### 5.3 注入设计（引擎侧统一收口）

```
平台 AgentExecutionEngine.buildTask ──┐
                                      ├─ 构建 AgentTask 时由运行时解析入口文件 → task.entryDirectives / entryDirectivesMeta
桌面 DesktopChatController ───────────┘
                       ↓
ReactAgentExecutor.buildMessages → SystemPromptComposer.compose(agent, ...)
  = agent.systemPrompt
  + # 调度入口指令（来源：project:AGENTS.md@1.3.0 / user-global:GEWU.md@null / none）   ← 新增段
  + 模式指令 + 思维指令
```

- **平台注入点（评审 F-203 更正）**：平台侧 AgentTask 的真实构建点是 `AgentExecutionEngine.buildTask`（AgentExecutionEngine.java:220 起，AiChatController 仅是 HTTP 入口），注入改造落在此处；桌面侧构建点为 `DesktopChatController`（:151-168）。
- **引擎不感知文件系统**：`AgentTask` 增加两个可选字段 `entryDirectives`（内容）与 `entryDirectivesMeta`（来源/版本/截断标记 JSON）；解析责任在各运行时适配层（平台=gewu-application 新服务，桌面=desktop-runtime 新 resolver）。引擎只在 compose 时追加一段，无内容则不追加（完全向后兼容）。
- 追加位置在 agent.systemPrompt **之后**、模式指令**之前**：语义上"用户/项目级规范"应压过"模式措辞"，又不覆盖智能体人设本体。
- **子代理继承**：spawn_subagents 构建子任务时经 `ToolContext` 传递父任务字段（ReactAgentExecutor:1399-1415）——`ToolContext` 需同步新增 `entryDirectives` 透传字段（评审 F-204），子任务不重复解析（与子代理共享工作空间的既有语义一致，:1404）。
- **长任务继承（评审 F-203）**：`LongTaskService` 分段执行自建 AgentTask（:315），P0 必须同步透传父会话已解析的 entryDirectives，否则同一会话的长任务段静默丢失入口指令。
- **编排路径（P2）**：VeloflowAiBridge、Supervisor/Swarm/Debate/Pipeline 模式处理器、PlanExecuteRuntime 另有 6 类 7 处 AgentTask 构建点（全仓共 11 处，清单见 §12.6），本期不注入，后续复用同一继承载体。
- 会话语义（实现修正，2026-10-21）：注入在**每条用户消息的任务构建时解析**（buildTask→applyToTask，60s 缓存）——会话中途改动入口文件，**下一条用户消息即生效**，无需专门刷新命令；"任务启动快照"仅指单轮内不重读。**边界明示（评审 F-209）**：无项目会话的会话根即卷根，`/workspace/GEWU.md` 对模型**可见且可改写**（模型写入经 V2M 同步持久化）——行为与文件工具语义一致、予以接受，缓解见 §8；全局文件对**项目会话**不可见（文件工具以会话根为边界，属预期行为）。

## 6. 平台端详细设计

### 6.1 解析服务 `AgentEntryFileService`（gewu-application）

```java
EntryResolution resolve(Long userId, String projectId /*可空*/):
  global  = minioService.getObject("workspaces/{userId}/GEWU.md"  → 否则 "…/AGENTS.md")   // 不依赖沙箱
  project = projectId == null ? null
          : sandboxOnline(projectId) ? sandboxClient.readFile(sandboxId, "projects/{id}/repo/GEWU.md" → 否则 AGENTS.md)
          : null /* 记因：sandbox-offline，跳过项目级 */
  return EntryFileSelector.select(global, project)   // §5.2 纯函数
```

- 全局级以 **MinIO 为权威源**：会话构建不触发 dev 沙箱拉起（现状 `ensureDevSandboxForUser` 是惰性重操作，读配置不应有此副作用）；用户在沙箱内直接创建的文件经 WorkspaceSyncService 5 秒队列 + 5 分钟对账进入 MinIO，下一会话生效。
- 项目级以**沙箱卷为源**（即 Git 仓库内容）：dev 沙箱在线才读，离线/读取失败降级为"仅全局级"，不阻塞会话。
- 缓存：进程内 `(userId, projectId) → (contentHash, resolution)` 缓存，TTL 60s，命中免 MinIO/沙箱往返；写路径主动失效。

### 6.2 API 与管理界面

| API | 说明 |
|---|---|
| `GET/PUT /api/v1/me/entry-file` | 用户全局入口文件读写。**PUT 复用 `WorkspaceService` 既有保存路径**（评审 F-202）：建 `workspace_file` 行 + 显式入队 M2V——同步服务只消费显式入队待办、不感知 MinIO 直写，绕过该路径直写 MinIO 会导致卷侧永不更新且对账兜底也覆盖不到；dev 沙箱在线时卷侧数秒内可见，未创建过沙箱的用户仅落 MinIO（其全局文件同样生效，见 §6.3） |
| `GET /api/v1/projects/{id}/entry-file` | 项目级入口文件只读（沙箱卷）；响应带 `inRepo` 标记 |
| `POST /api/v1/projects/{id}/entry-file/init` | 用平台模板初始化项目级文件（**仅在 clone 成功后可调**——clone 要求目标目录为空，先写后 clone 会失败，ProjectRepoService.java:60-63） |

- 设置页新增"全局调度指令"编辑器（Markdown 文本域 + 版本号字段 + 保存）；
- 项目详情新增"调度文件"页签：查看/初始化模板/一键跳转文件工具；
- 模板内容提供两档：空白模板、治理模板（参照本仓库 AGENTS.md 的"能力注册表 + 冲突裁决"骨架，面向用格物管研发流程的团队）。

### 6.3 时序与边界

- **clone 与初始化时序**：项目级文件主来源是 Git 仓库自带（clone 即得）；UI 初始化模板只对"空仓库/无入口文件"项目开放，写入后提示"建议提交进仓库，团队共享"。
- **`projects/{projectName}` 平行克隆路径**（DevWorkspaceService.java:331，无 /repo 后缀）：解析按 `session.directory` 实际值拼路径，不写死 `/repo`，避免双路径不一致踩坑。
- **无沙箱用户**（纯对话/临时会话）：仅全局级生效，来源 MinIO；该用户后续首次创建 dev 沙箱时无需额外动作——PUT 已建台账行并登记 M2V，对账兜底（reconcile M2V 遍历全部已登记行）在沙箱就绪后自动把已登记 md 种子进卷（评审 F-202 补；实现上不侵入 `ensureDevSandboxForUser`，避开 WorkspaceSyncService↔DevWorkspaceService 环依赖）。
- **全局文件编辑建议走设置页**（直写 MinIO 即时生效）；沙箱内手动创建的文件经同步延迟（5 秒队列 + 5 分钟对账）后才对"无沙箱会话"可见。

### 6.4 升级通知（v1.2 新增·替代静默覆盖）

**触发条件**（评审 F-304 修正措辞）：全局文件带版本，且项目级文件**无版本头或版本较低**——即 §5.2 规则 2a 未让全局胜出的场景（无版本时不谈"更高"，只谈"未参与协议"）。**惰性计算**：项目调度文件页签打开时比对，不推送不打扰，不参与会话内解析；桌面端同等能力列 P2。

- 载体：页签横幅——"全局规范已升级至 x.y.z，当前项目文件{无版本头｜版本 a.b.c}，[查看差异] [一键同步]"；项目级文件不存在时不展示横幅（无同步对象，走 §6.2 模板初始化通道）。
- **一键同步限同名**（评审 F-302）：全局与项目级为**同名文件**（GEWU.md↔GEWU.md / AGENTS.md↔AGENTS.md）才提供覆盖；跨名场景（如全局 GEWU.md 对项目 AGENTS.md）仅提示内容差异、**不提供同步**——格物专属语义不得借同步写入生态共用的标准文件（§5.1"标准文件保持原味"）。
- **覆盖前强制快照**（评审 F-301）：同步写入前，服务端将项目级文件当前内容快照至 MinIO `workspaces/{userId}/snapshots/entry-file/{ts}`（保留最近 10 份或 90 天，先到为准，到期清理），横幅提供"恢复快照"入口（沙箱离线时与同步同样置灰）；已提交入 Git 的文件亦可经 Git 回滚（双保险）。未跟踪文件（§12 风险4 场景）的回滚**只依赖快照**，不再声称依赖 Git。
- 写路径与可用性（评审 S4 残留修正）：同步经 `SandboxClient.writeFile` 直接写入沙箱卷（项目级权威源，即生效，无需 workspace_file 建行登记——`projects/**` 不在同步服务白名单内）；dev 沙箱离线时"一键同步"置灰不可用。

## 7. 桌面端详细设计

### 7.1 `EntryFileResolver`（gewu-desktop-runtime）

```java
EntryResolution resolve(String projectDirectory /*可空*/):
  global  = ~/.gewu/GEWU.md → ~/.gewu/AGENTS.md            // gewuRoot 既有约定（main.js:548）
  project = projectDirectory == null ? null
          : {projectDirectory}/GEWU.md → {projectDirectory}/AGENTS.md
  return EntryFileSelector.select(global, project)          // 与平台同一纯函数
```

- `DesktopChatController` 构建 AgentTask 时调用并填充 `task.entryDirectives`（151-168 行处，系统提示词注入）；
- **`ProjectContextTool` 改造**：AGENTS.md 段改由 `EntryFileResolver` 输出（含 GEWU.md 与版本裁决），工具继续追加 rules/*.md 与 skills 装载——保证"自动注入"与"按需读取"看到同一份入口文件，杜绝双源分叉；
- 文件监视（WatchService）可选：检测 `~/.gewu` 与项目根的入口文件变更后刷新，P1 简化为"每任务构建时实时读"（本地 FS 读成本可忽略，无需缓存）。

### 7.2 管理界面

- 设置面板新增"调度入口文件"区块：全局文件编辑器 + "在当前项目创建/编辑"按钮（复用既有 FileEditorPanel）；
- 首次启动检测到无全局文件时，设置项显示引导（一键生成模板）。

## 8. 安全与边界

1. **路径安全**：平台端全部路径为服务端拼接的受控相对路径，复用 `ContainerFileService.validatePath`（拒绝对路径/`..`）；桌面端复用 `PathGuard`（realpath 防符号链接逃逸）。
2. **大小与编码**：64KB 单文件读取上限、32KB 注入上限（均按字节，§5.1）、非 UTF-8 按替换字符降级，杜绝超大文件挤爆上下文。
3. **信任分层（评审 F-205）**：入口文件按来源分两档信任——
   - **全局级 = 用户自写**（设置页/桌面 `~/.gewu`），等价于用户亲手写的 system prompt，高信任；
   - **项目级 = 仓库自带**，作者可能是第三方仓库维护者，构成**仓库侧提示词注入威胁面**（恶意仓库借 AGENTS.md 指使智能体）。缓解：`entryDirectivesMeta` 强制携带来源标注（project 路径 + Git 仓库标识）供审计与前端展示；平台治理模板默认带版本头，作用是**参与 §6.4 升级治理协议**（opt-in，非注入缓解手段）；平台红线门禁（凭证三禁、命令门禁、工具审批）**独立于该文件执行**——文件里写"允许 rm -rf"不构成放行，裁决权在平台门禁层。与业界行为一致（各引擎均自动读取仓库自带 AGENTS.md），取舍明示不隐藏。
4. **模型写入边界（评审 F-209）**：无项目会话中模型可经文件工具改写 `/workspace/GEWU.md` 并经 V2M 持久化（§5.3），等于模型可以修改自己的全局指令——接受该行为（与文件工具既有语义一致），缓解：审计日志记录 contentSha256 变更链，异常改写可回溯。
5. **秘钥泄漏防护（P2）**：保存/初始化时对内容做密钥特征扫描（`sk-`、`BEGIN PRIVATE KEY`、`AKIA` 等），命中则告警不落库。P1 上线 PUT/初始化 API 至 P2 扫描落地之间存在**防护缺位期**，已在 §12 风险表登记豁免。
6. **审计**：注入即留痕（§9），可回溯任何会话"当时看到的是哪份规范"。

## 9. 留痕与可观测

- 每次任务构建追加一条结构化日志：`entry_file_resolved {userId, projectId, chosenSource, chosenVersion, reason, contentSha256, bytes}`；
- `entryDirectivesMeta` 随会话透出：前端会话详情"上下文"页展示"调度入口：project:AGENTS.md（项目级默认优先）"或"调度入口：user-global:GEWU.md@1.3.0（双方声明版本，版本高者优先）"，让用户看得见生效来源；
- 对接 AETC 留痕思路：审计目录追加 JSONL 行（与现有 audit/traces 同 schema）。

## 10. 分期实施计划

| 阶段 | 内容 | 涉及模块 | 估时 |
|---|---|---|---|
| **P0 引擎底座** | `EntryFileSelector` 纯函数 + semver 比较（gewu-common，表驱动单测）；`AgentTask.entryDirectives/entryDirectivesMeta` 字段；`SystemPromptComposer` 追加段；`ToolContext.entryDirectives` 透传（子代理继承）；`LongTaskService` 长任务段继承 | gewu-common、gewu-agent-engine | 1~1.5d |
| **P0 桌面端** | `EntryFileResolver`；DesktopChatController 注入；ProjectContextTool 复用 resolver；设置面板编辑器；contract test 扩展 | gewu-desktop-runtime、gewu-desktop | 1d |
| **P1 平台端** | `AgentEntryFileService`（MinIO 全局 + 沙箱项目级 + 缓存 + 种子同步（对账自愈））；三个 REST API（PUT 复用 WorkspaceService 保存路径）；设置页编辑器 + 项目页签 + 模板初始化 + 升级通知（§6.4）；集成测试 | gewu-application、gewu-interface、gewu-web | 2~3d |
| **P2 增强**（批次二已实施·2026-10-21） | ✅ merge 叠加模式（`gewu.entry.layering`/`gewu.desktop.entry-layering: select\|merge`，项目 frontmatter `override: true` 时项目独占，reason=merged/project-override）；✅ `@path` import（EntryImportExpander：整行 @相对路径、单层展开、上限 5 处、防环防越界，两端接入）；✅ 桌面端升级提示（resolver 计算 + 设置面板横幅）；✅ Wenshi 旁路注入（reasonStream/steering/callLlm 共 15 处拼接点接入 entrySuffix）；✅ 会话内刷新（实现为逐轮解析，文档语义修正 §5.3） | 多模块 | 1d |
| **P2 占位**（后续批次） | 子目录嵌套发现（monorepo）——依赖引擎文件工具层先落"当前工作子目录"语义，需单独设计；其余均已在批次一/二落地 | gewu-agent-engine | 另评 |

## 11. 测试与验收

1. **算法单测**（表驱动）：双方带版本异版（→高版本胜）/双方带版本同版（→项目级胜）/单边缺版本（→**项目级胜**，主流就近兜底·评审质询修订）/双缺版本（→项目级胜）/双方都不存在（→none，不追加注入段）/非法版本串（→视同无版本）/GEWU 与 AGENTS 同目录并存（→GEWU 胜，同层版本不参与裁决；**发现层用例**，非选择器输入契约）/超限截断；
2. **平台集成测试**：MinIO 全局单飞；沙箱离线降级；clone 后仓库自带文件生效；**导入无版本头外部 AGENTS.md + 全局带版本 → 项目文件生效（零修改）**；PUT 全局（经 WorkspaceService 保存路径建行+入队）→ 卷侧数秒可见且下一会话生效；
3. **桌面契约测试**：`DesktopProfileContractTest` 扩展断言 resolver 行为与 project.context 输出一致；
4. **E2E 冒烟**：项目仓库放 `AGENTS.md version: 1.0.0` + 用户全局 `GEWU.md version: 1.1.0`（双方带版本）→ 新会话 system prompt 含 GEWU 内容且 meta 标注 `version-higher`；删全局文件 → 回落项目级；项目仓库放**无版本头** AGENTS.md + 全局 1.1.0 → **项目级胜（project-default）**且页签出现升级提示，跨名（GEWU↔AGENTS）**不提供一键同步**（§6.4 限同名）；
5. **验收口径**：模型在对话中能正确复述入口文件声明的项目约定（人工抽检 3 类问题：构建命令、代码风格、技能入口）。

## 12. 风险与开放问题

| # | 问题 | 倾向 |
|---|---|---|
| 1 | **单选 vs 拼接**：业界拼接派（Claude Code/ZCode）上下文更全，单选派（agents.md 规范）更可控。格物用户规则为单选+双方 opt-in 版本裁决，但全局规范 + 项目特例并存是常见诉求 | P1 按单选落地；P2 评估 merge 模式（全局段 + 项目段拼接，项目声明 `override: true` 时才单选） |
| 2 | **版本协议的约束力边界（v1.2 修订）**：opt-in 后，全局模板对未参与版本协议的项目（无版本头文件）不再有静默约束力 | 升级感知由 §6.4 显式通知承担（查看差异/一键同步）；治理型团队建议全程使用 GEWU.md + 版本头参与协议 |
| 3 | **MinIO 同步延迟**：沙箱内手建的全局文件最多 5 分钟（对账兜底）才可被无沙箱会话读到 | 文档明示"建议从设置页编辑全局文件"（直写 MinIO，零延迟） |
| 4 | **项目级文件污染 Git 状态**：UI 初始化写入的是未跟踪文件 | 提示提交入库；`inRepo` 标记区分 |
| 5 | **与既有 `project.context` 工具的关系**（桌面端） | 统一 Resolver 后工具降级为"补充装载 rules/skills"，入口文件以自动注入为准 |
| 6 | **编排路径未覆盖（已清偿·P2 批次一）**：~~VeloflowAiBridge:75、SupervisorModeHandler:97、SwarmModeHandler:107、DebateModeHandler:73/139、PipelineModeHandler:346、PlanExecuteRuntime:106 共 6 类 7 处 AgentTask 构建点不注入~~——OrchestrationContext 继承载体 + 6 处 builder + 编排发起解析已落地；VeloflowAiBridge/AutonomousExecutor 为系统合成身份记 N/A；AgentRoleSpec.systemPromptTemplate 仍预留未填、Wenshi 旁路已于批次二接入 | 已清偿（编排）；Wenshi 已接入（批次二） |
| 7 | **P1 密钥扫描缺位豁免期**：~~PUT/初始化 API 于 P1 上线，密钥特征扫描 P2 才有~~ **已清偿（P2 批次一）**：SecretScanUtil 特征扫描接入平台保存/初始化与桌面保存入口，命中即拒绝落库 | 已清偿 |
| 8 | **多智能体/编排提示词改造**（Wenshi 推理引擎/多智能体协调器硬编码） | 另立专项，不随本设计分期 |
