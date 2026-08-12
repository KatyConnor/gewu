# 格物平台 — 前端功能需求分析报告

> **版本**: v1.0  
> **日期**: 2026-07-20  
> **分析范围**: gewu-web 前端全部页面、组件、状态管理、API 对接  
> **分析深度**: 逐菜单按钮、功能按钮、页面字段、类型、设计逻辑、隐藏逻辑  

---

## 目录

1. [系统架构概览](#1-系统架构概览)
2. [导航结构分析](#2-导航结构分析)
3. [页面逐项分析](#3-页面逐项分析)
4. [状态管理分析](#4-状态管理分析)
5. [API 对接分析](#5-api-对接分析)
6. [设计体系分析](#6-设计体系分析)
7. [隐藏逻辑汇总](#7-隐藏逻辑汇总)
8. [数据字典](#8-数据字典)
9. [待完善事项](#9-待完善事项)

---

## 1. 系统架构概览

### 1.1 技术栈

| 层 | 技术 |
|---|------|
| 框架 | Next.js 14 (App Router) + React 18 + TypeScript |
| 状态管理 | Redux Toolkit (@reduxjs/toolkit) |
| 样式 | Tailwind CSS + CSS 变量（墨韵绿主题系统） |
| HTTP 客户端 | Axios |
| 图标 | Lucide React |
| 构建 | Next.js Build |

### 1.2 目录结构

```
gewu-web/src/
├── app/
│   ├── layout.tsx          # 根布局
│   └── page.tsx            # 入口页（渲染 LoginPage 或主框架）
├── components/
│   ├── layout/
│   │   ├── Header.tsx      # 顶部导航栏
│   │   └── Sidebar.tsx     # 侧边导航栏
│   ├── pages/
│   │   ├── LoginPage.tsx       # 登录页
│   │   ├── DashboardPage.tsx   # 主页仪表盘
│   │   ├── ChatPage.tsx        # 会话/聊天页
│   │   ├── ChatHomeView.tsx    # 聊天首页视图
│   │   ├── ProjectsPage.tsx    # 项目管理页
│   │   ├── RequirementsPage.tsx# 需求管理页
│   │   ├── PrototypePage.tsx   # 原型设计页
│   │   ├── WorkflowPage.tsx    # 工作流页
│   │   ├── WorkflowCanvas.tsx  # 工作流画布
│   │   ├── AgentMarketPage.tsx # 智能体广场页
│   │   ├── MyAgentsPage.tsx    # 我的智能体页
│   │   ├── AgentManagePage.tsx # 智能体管理页
│   │   ├── SkillLibraryPage.tsx# 技能库页
│   │   ├── MySkillsPage.tsx    # 我的技能页
│   │   ├── SandboxPage.tsx     # 沙箱安全页
│   │   ├── McpServerPage.tsx   # MCP Server 页
│   │   ├── UsagePage.tsx       # 用量统计页
│   │   ├── SettingsPage.tsx    # 设置页
│   │   └── AIThinkingProcess.tsx # AI 思考过程组件
│   ├── ui/
│   │   ├── Toast.tsx       # 消息提示
│   │   ├── Select.tsx      # 自定义下拉选择
│   │   └── (Card 引用但未找到定义)
│   ├── ThemeSync.tsx       # 主题同步
│   └── ErrorBoundary.tsx   # 错误边界
├── lib/
│   ├── api.ts              # API 端点配置
│   ├── auth.ts             # 认证服务（登录/注册/刷新/登出）
│   ├── request.ts          # Axios 请求实例 + 拦截器
│   ├── token.ts            # Token 管理工具
│   └── themes.ts           # 主题配置
├── store/
│   ├── index.ts            # Redux Store + Slice
│   └── Providers.tsx       # Redux Provider
└── types/
    └── index.ts            # TypeScript 类型定义
```

### 1.3 路由机制

前端**不使用 Next.js 文件路由**，而是通过 Redux `currentPage` 状态切换页面组件：

```typescript
// page.tsx 核心逻辑
const currentPage = useSelector((s: RootState) => s.app.currentPage);
// 根据 currentPage 渲染不同页面组件
```

---

## 2. 导航结构分析

### 2.1 侧边栏 Sidebar 导航

**组件**: `Sidebar.tsx`  
**位置**: 左侧固定，宽度 `w-64`  
**分组**: 3 个大组，共 16 个导航项

#### 工作空间组

| 菜单项 | PageType | 图标 | Badge | 颜色 | 行为 |
|--------|----------|------|-------|------|------|
| 主页 | `dashboard` | Home | - | 默认 | `dispatch(setPage('dashboard'))` |
| 会话 | `chat` | MessageSquare | `3` | 默认 | `dispatch(setPage('chat'))` |
| 项目管理 | `projects` | FolderOpen | - | 默认 | `dispatch(setPage('projects'))` |
| 需求管理 | `requirements` | FileText | `12` | 默认 | **noAction: true** — 点击无效果 |
| 原型 | `prototype` | PenTool | - | 默认 | `dispatch(setPage('prototype'))` |
| 工作流 | `workflow` | GitBranch | - | 默认 | `dispatch(setPage('workflow'))` |

#### 智能体组

| 菜单项 | PageType | 图标 | Badge | 颜色 | 行为 |
|--------|----------|------|-------|------|------|
| 智能体广场 | `agent-market` | Bot | - | `text-cyber-400/70` | `dispatch(setPage('agent-market'))` |
| 我的智能体 | `my-agents` | Shield | - | `text-cyber-400/70` | `dispatch(setPage('my-agents'))` |
| 智能体管理 | `agent-manage` | Bot | - | `text-cyber-400/70` | `dispatch(setPage('agent-manage'))` |
| 技能库 | `skill-library` | BookOpen | - | `text-cyber-400/70` | `dispatch(setPage('skill-library'))` |
| 我的技能 | `my-skills` | Zap | - | `text-cyber-400/70` | `dispatch(setPage('my-skills'))` |
| 沙箱安全 | `sandbox` | Lock | - | `text-cyber-400/70` | `dispatch(setPage('sandbox'))` |
| MCP Server | `mcp-server` | Server | - | `text-cyber-400/70` | `dispatch(setPage('mcp-server'))` |

#### 数据组

| 菜单项 | PageType | 图标 | Badge | 颜色 | 行为 |
|--------|----------|------|-------|------|------|
| 用量统计 | `usage` | BarChart3 | - | 默认 | **noAction: true** — 点击无效果 |
| 设置 | `settings` | Settings | - | 默认 | `dispatch(setPage('settings'))` |

#### 隐藏逻辑

1. **noAction 项**: `requirements` 和 `usage` 设置了 `noAction: true`，点击时 `onClick` 回调被短路，不会执行 `dispatch(setPage(...))`
2. **Badge 显示**: 只有 `chat` (badge: '3') 和 `requirements` (badge: '12') 显示数字徽章
3. **激活态样式**: 当前页面匹配时，按钮使用 `nav-item active` 样式 + 图标变 `text-tech-400`
4. **智能体组颜色**: 所有智能体组图标在非激活态使用 `text-cyber-400/70`（淡蓝色），区别于工作空间组
5. **底部用户信息**: 显示 `user.avatar`（头像首字）、`user.name`、`user.department · user.role`
6. **底部按钮切换**: 在 `dashboard` 页时显示设置按钮，其他页时显示返回按钮

### 2.2 顶部栏 Header

**组件**: `Header.tsx`  
**位置**: 顶部固定，`left-64`（避开侧边栏），高度 `h-16`

| 按钮 | 图标 | 行为 | 隐藏逻辑 |
|------|------|------|----------|
| 消息提醒 | Bell | 无（仅展示） | 红色圆点指示器（永远显示） |
| 主题切换 | Palette | 展开/收起主题下拉 | 下拉显示 4 个主题选项，当前主题显示 ✓ |
| 设置（齿轮） | SVG | `toast('设置功能开发中', 'info')` | 仅显示开发中提示 |
| 退出登录 | LogOut | 清除 localStorage + `dispatch(setPage('login'))` | 仅清除 `gewu-remember` 和 `gewu-email`，未清除 token |

---

## 3. 页面逐项分析

### 3.1 登录页 LoginPage

**PageType**: `login`（默认首页）  
**路由守卫**: 无 — 通过 Redux `currentPage` 控制显示

#### 密码登录表单

| 字段 | 类型 | 验证 | 隐藏逻辑 |
|------|------|------|----------|
| 账号/邮箱 | `input type="text"` | 非空校验 | 使用 `ref` 非受控组件（解决 autofill 问题） |
| 密码 | `input type="password"` | 非空校验 | 点击眼睛图标切换 `showPassword` |
| 记住我 | `checkbox` | - | 选中时将 email 存入 `localStorage` |

#### 企业 SSO 表单

| 字段 | 类型 | 验证 | 隐藏逻辑 |
|------|------|------|----------|
| 企业域名 | `input type="text"` | - | 后缀 `.sso.com` 固定不可编辑 |

#### 功能按钮

| 按钮 | 行为 | 隐藏逻辑 |
|------|------|----------|
| 登录 | `handleLogin` → 调用 `login()` API | `loading` 时禁用，显示"登录中..." |
| 注册新账户 | `handleRegister` → 调用 `register()` API | `loading` 时禁用 |
| 忘记密码 | 弹出重置弹窗 | 弹窗内可输入邮箱发送重置链接 |
| 钉钉/企微/飞书 | `toast('XX 登录功能开发中', 'info')` | 仅显示开发中提示 |

#### API 对接

| 操作 | 端点 | 请求体 | 响应 |
|------|------|--------|------|
| 登录 | `POST /api/v1/auth/login` | `{ username, password }` | `Result<TokenDTO>` |
| 注册 | `POST /api/v1/auth/register` | `{ username, email, password, displayName }` | `Result<TokenDTO>` |

#### 登录成功流程

```
login(data) → saveTokens(accessToken, refreshToken) → saveUser(userInfo) → dispatch(setAuth(...))
```

#### 隐藏逻辑

1. **autofill 检测**: 通过 `animationstart` 事件监听 `onAutoFillStart` 动画
2. **SSO 登录未实现**: 点击 SSO 登录按钮仅显示提示
3. **退出登录不完整**: Header 的退出按钮未清除 `gewu-access-token` 和 `gewu-refresh-token`

---

### 3.2 主页仪表盘 DashboardPage

**PageType**: `dashboard`  
**数据源**: 全部硬编码静态数据，无 API 调用

#### 统计卡片

| 指标 | 值 | 变化 | 图标 | 特殊 |
|------|-----|------|------|------|
| 今日会话 | `24` | ↑ 12% 较昨日 | MessageSquare | - |
| 活跃智能体 | `8` | 3 个正在执行任务 | Bot | - |
| 项目进度 | `67%` | 5个项目进行中 | FolderOpen | - |
| Token 用量 | `128K` | 剩余额度 72% | Zap | 显示进度条（28%） |

#### 最近会话列表

| 字段 | 类型 | 点击行为 |
|------|------|----------|
| 标题 | string | `dispatch(setPage('chat'))` |
| 智能体名称 | string | - |
| 时间 | string | - |
| 消息数 | number | - |

#### 待办事项列表

| 字段 | 类型 | 交互 |
|------|------|------|
| 标题 | string | 可勾选 checkbox |
| 截止时间 | string | - |
| 紧急标记 | boolean | 紧急=红色圆点，非紧急=金色圆点 |

#### 快速操作

| 按钮 | 行为 |
|------|------|
| 新建对话 | `dispatch(setPage('chat'))` |
| 新建项目 | `dispatch(setPage('projects'))` |

---

### 3.3 会话页 ChatPage

**PageType**: `chat`  
**布局**: 左侧会话列表(始终显示) + 右侧聊天区域

#### 视图切换逻辑

```
showChatView = false → 显示 ChatHomeView（聊天首页）
showChatView = true  → 显示聊天对话视图
```

#### 左侧会话列表

| 元素 | 类型 | 行为 |
|------|------|------|
| 搜索框 | input | 仅 UI，无搜索逻辑 |
| 新建按钮 | button | `startNewChat()` → 切换到聊天视图 |
| 会话 Tab | 4 个 Tab | `all`/`project`/`requirement`/`test` |
| 会话分组 | 按日期分组 | 点击加载对话 |

#### 聊天视图

| 元素 | 类型 | 行为 |
|------|------|------|
| 标题 | string | 显示 `currentTitle` |
| 设置按钮 | button | 无功能 |
| 分享按钮 | button | 无功能 |
| 历史按钮 | button | 无功能 |
| 消息列表 | Message[] | 显示用户/AI 消息气泡 |
| AI 思考过程 | AIThinkingProcess | 可展开/收起 |
| 输入框 | textarea | Enter 发送，Shift+Enter 换行 |
| 发送按钮 | button | `sendMessage()` |
| 上传文件 | button | 仅 UI |
| 引用上下文 | button | 仅 UI |

#### 模型配置下拉

| 下拉框 | 选项 | 当前值 |
|--------|------|--------|
| 模型 | GPT-4o / GPT-4o Mini / Claude 3.5 Sonnet / Claude 3 Opus / DeepSeek V3 / Qwen Max | `gpt-4o` |
| 智能体模式 | 助手模式 / 专家模式 / 创意模式 / 精确模式 | `assistant` |
| 思维方式 | 链式推理 / 树状推理 / ReAct / 逐步分析 / 苏格拉底式 | `chain-of-thought` |

#### 隐藏逻辑

1. **模拟流式响应**: 使用 `useChatStream` hook 模拟 AI 流式响应，未对接真实 API
2. **会话数据硬编码**: `chatData.ts` 中的 `chatGroups` 和 `aiResponses` 为静态数据
3. **思考过程模拟**: 4 个思考步骤使用 setTimeout 模拟进度
4. **消息发送限制**: `isStreaming` 时禁止发送新消息
5. **自动滚动**: 新消息或流式输出时自动滚动到底部

---

### 3.4 项目管理页 ProjectsPage

**PageType**: `projects`  
**视图模式**: 看板 / 列表 / 甘特图

#### 看板视图（默认）

| 列 | 颜色 | 项目数 |
|----|------|--------|
| 进行中 | `bg-tech-400` | 3 |
| 待启动 | `bg-mist-400` | 2 |
| 已上线 | `bg-jade-500` | 2 |
| 已暂停 | `bg-cinnabar-400` | 1 |

#### 项目卡片字段

| 字段 | 类型 | 说明 |
|------|------|------|
| 项目名称 | string | - |
| 分类 | enum | 产品/技术/运营/已完成 |
| 描述 | string | 最多 2 行 |
| 进度 | number | 进度条显示 |
| 成员 | string[] | 头像首字母数组 |

#### 新建项目弹窗

| 字段 | 类型 | 必填 | 默认值 | 只读 |
|------|------|------|--------|------|
| 项目编号 | text | - | `PRJ-${Date.now()}` | ✅ 自动生成 |
| 项目类型 | select | ✅ | `product` | - |
| 项目名称 | text | ✅ | - | - |
| 项目状态 | text | - | `立项申请` | ✅ 不可修改 |
| 项目提出人 | text | - | `张明远` | ✅ 当前用户 |
| 提出时间 | text | - | 当前日期 | ✅ 默认当前时间 |
| 项目描述 | textarea | - | - | - |
| 计划开始时间 | date | - | - | - |
| 计划结束时间 | date | - | - | - |
| 主板系统 | select | - | `core` | - |
| 关联智能体 | select | - | `doc` | - |

#### 功能按钮

| 按钮 | 行为 | 隐藏逻辑 |
|------|------|----------|
| 搜索 | 仅 UI | 无搜索逻辑 |
| 新建项目 | 打开创建弹窗 | - |
| 视图切换 | 看板/列表/甘特图 | 甘特图显示"开发中..." |
| 状态筛选 | 下拉选择 | 仅 UI |
| 成员筛选 | 下拉选择 | 仅 UI |
| 创建 | `handleCreate()` | 仅校验项目名称非空 |

---

### 3.5 智能体管理页 AgentManagePage

**PageType**: `agent-manage`  
**数据源**: 硬编码静态数据（8 个智能体）

#### 统计概览

| 指标 | 说明 |
|------|------|
| 智能体总数 | 总数 |
| 运行中 | `status=active` 计数 |
| 已暂停 | `status=inactive` 计数 |
| 已下架 | `status=archived` 计数 |

#### 筛选

| 控件 | 类型 | 选项 |
|------|------|------|
| 搜索框 | input | 按名称/描述/ID 模糊匹配 |
| 状态筛选 | select | 所有状态/运行中/已暂停/已下架/异常 |

#### 智能体卡片字段

| 字段 | 类型 | 说明 |
|------|------|------|
| ID | string | 如 `AG001` |
| 名称 | string | - |
| 描述 | string | 最多 2 行 |
| 状态 | enum | draft/published/active/inactive/archived/error/destroyed |
| 版本 | number | 如 `v3` |
| 模型提供商 | string | OpenAI/Anthropic/DeepSeek |
| 模型名称 | string | 如 `GPT-4o` |
| 对话次数 | number | - |
| 上次使用 | string | 相对时间 |

#### 状态配置

| 状态 | 颜色 | 图标 | 标签 |
|------|------|------|------|
| draft | `text-ink-400` | Edit | 草稿 |
| published | `text-tech-400` | CheckCircle | 已发布 |
| active | `text-green-400` | Play | 运行中 |
| inactive | `text-gold-400` | Pause | 已暂停 |
| archived | `text-ink-400` | Archive | 已下架 |
| error | `text-cinnabar-400` | XCircle | 异常 |
| destroyed | `text-cinnabar-400` | Trash2 | 已销毁 |

#### 操作菜单（⋮ 按钮）

| 菜单项 | 显示条件 | 行为 |
|--------|----------|------|
| 查看详情 | 始终显示 | 打开右侧抽屉 |
| 编辑配置 | 始终显示 | 仅关闭菜单 |
| 复制智能体 | 始终显示 | 仅关闭菜单 |
| 发布到广场 | `!published && status !== archived && status !== destroyed` | 仅关闭菜单 |
| 取消发布 | `published` | 仅关闭菜单 |
| 停止 | `status === active` | 仅关闭菜单 |
| 启动 | `status === inactive` | 仅关闭菜单 |
| 下架 | `status !== archived && status !== destroyed` | 仅关闭菜单 |
| 销毁 | 始终显示 | 仅关闭菜单 |

#### 创建智能体弹窗

| 字段 | 类型 | 必填 | 选项 |
|------|------|------|------|
| 智能体名称 | text | ✅ | - |
| 模型提供商 | select | ✅ | OpenAI / Anthropic / DeepSeek |
| 模型名称 | select | ✅ | GPT-4o / GPT-4o-mini / o1-preview |
| 初始状态 | select | - | 立即启动 / 稍后启动 |
| 描述 | textarea | - | - |
| 系统提示词 | textarea | - | 等宽字体 |
| 模型配置 | textarea | - | JSON 格式 |

#### 详情抽屉

| 字段 | 类型 |
|------|------|
| 名称 + 版本 | 头部显示 |
| 状态 | 带颜色标签 |
| 对话次数 | 数字 |
| 模型 | 提供商/名称 |
| 上次使用 | 相对时间 |
| 描述 | 文本 |
| 生命周期操作 | 按钮组（同操作菜单） |

---

### 3.6 智能体广场页 AgentMarketPage

**PageType**: `agent-market`  
**数据源**: 硬编码（12 个智能体，仅显示 `published=true` 的 8 个）

#### 分类标签

| ID | 标签 | 图标 |
|----|------|------|
| all | 全部 | Bot |
| document | 文档处理 | FileText |
| code | 代码开发 | Code |
| data | 数据分析 | BarChart3 |
| security | 安全审计 | Shield |
| meeting | 会议协作 | MessageSquare |
| design | 产品设计 | Zap |

#### 智能体卡片字段

| 字段 | 类型 | 说明 |
|------|------|------|
| 名称 | string | - |
| 分类 | enum | document/code/data/security/meeting/design |
| 描述 | string | - |
| 星级 | number | 如 4900 |
| 安装量 | string | 如 `12.3k` |
| 作者 | string | 固定 `格物致虚` |
| 标签 | string[] | 3 个标签 |

#### 隐藏逻辑

1. **只显示已发布**: `const agents = allAgents.filter(a => a.published)` 过滤掉未发布的 4 个
2. **安装按钮**: 仅显示，点击无效果

---

### 3.7 工作流页 WorkflowPage

**PageType**: `workflow`  
**视图模式**: 列表 / 画布

#### 工作流列表

| 字段 | 类型 | 说明 |
|------|------|------|
| 名称 | string | - |
| 描述 | string | - |
| 设计状态 | enum | draft / published |
| 运行状态 | enum | stopped / running / paused |
| 触发器数 | number | - |
| 上次运行 | string | 相对时间 |
| 成功率 | number | 百分比 |
| 节点数 | number | - |

#### 功能按钮

| 按钮 | 行为 | 隐藏逻辑 |
|------|------|----------|
| 创建流程 | 打开创建弹窗 | - |
| 搜索 | 仅 UI | 无搜索逻辑 |
| 状态筛选 | 下拉选择 | 仅 UI |
| 视图切换 | 列表/画布 | - |
| 操作菜单 | 每个流程卡片 | ⋮ 按钮展开 |

#### 操作菜单

| 菜单项 | 行为 |
|--------|------|
| 编辑 | 切换到画布视图 |
| 运行/停止 | 仅关闭菜单 |
| 复制 | 仅关闭菜单 |
| 导出 | 仅关闭菜单 |
| 重置运行 | 仅关闭菜单 |
| 删除 | 仅关闭菜单 |

---

### 3.8 沙箱安全页 SandboxPage

**PageType**: `sandbox`  
**数据源**: 硬编码（5 个沙箱）

#### 统计概览

| 指标 | 说明 |
|------|------|
| 总数 | 所有沙箱 |
| 运行中 | `status=running` |
| 已停止 | `status=stopped` |
| 异常 | `status=error` |

#### 沙箱卡片字段

| 字段 | 类型 | 说明 |
|------|------|------|
| 名称 | string | - |
| 镜像 | string | Docker 镜像名 |
| 状态 | enum | running/stopped/creating/error |
| CPU | number | 核数 |
| 内存 | number | MB |
| 磁盘 | number | MB |
| IP | string | 未分配显示 `-` |
| 创建时间 | string | 日期 |
| 过期时间 | string | 日期或 `永久` |
| 来源 | enum | manual/agent/workflow |

#### 状态配置

| 状态 | 颜色 | 图标 | 标签 |
|------|------|------|------|
| running | `text-green-400` | CheckCircle | 运行中 |
| stopped | `text-ink-400` | Square | 已停止 |
| creating | `text-tech-400` | Loader2 (旋转) | 创建中 |
| error | `text-cinnabar-400` | XCircle | 异常 |

---

### 3.9 设置页 SettingsPage

**PageType**: `settings`  
**选项卡**: 通用 / 安全 / 通知 / 模型

#### 通用设置

| 字段 | 类型 | 默认值 | 只读 |
|------|------|--------|------|
| 头像 | 按钮 | - | - |
| 姓名 | text | `张明远` | - |
| 邮箱 | email | `zhang@company.com` | - |
| 手机号 | tel | `138****8888` | - |
| 部门 | text | `产品部` | - |
| 职位 | text | `高级总监` | - |
| 主题色 | 4 个色板 | `ink` | - |
| 语言 | select | `zh-CN` | - |
| 时区 | select | `Asia/Shanghai` | - |

#### 模型设置

| 元素 | 说明 |
|------|------|
| 提供商列表 | 6 个默认提供商（OpenAI/Anthropic/DeepSeek/Qwen/智谱/豆包） |
| 模型列表 | 4 个默认模型 |
| 添加提供商 | 打开弹窗 |
| 添加模型 | 打开弹窗 |

#### 提供商卡片字段

| 字段 | 类型 | 说明 |
|------|------|------|
| 名称 | string | - |
| Logo 字母 | string | 首字母 |
| 描述 | string | - |
| 模型列表 | string[] | 最多显示 4 个 |
| 模型数量 | number | - |
| 状态 | enum | active/inactive |

---

### 3.10 其他页面简要分析

| 页面 | PageType | 状态 | 说明 |
|------|----------|------|------|
| 需求管理 | `requirements` | 占位 | 导航标记 `noAction`，页面未实现 |
| 原型设计 | `prototype` | 占位 | 页面存在但未详细分析 |
| 我的智能体 | `my-agents` | 占位 | 页面存在但未详细分析 |
| 技能库 | `skill-library` | 占位 | 页面存在但未详细分析 |
| 我的技能 | `my-skills` | 占位 | 页面存在但未详细分析 |
| MCP Server | `mcp-server` | 占位 | 页面存在但未详细分析 |
| 用量统计 | `usage` | 静态 | 导航标记 `noAction`，页面有完整 UI 但数据硬编码 |

---

## 4. 状态管理分析

### 4.1 Redux Store 结构

```typescript
interface AppState {
  currentPage: PageType;       // 当前页面标识
  theme: ThemeType;            // 当前主题 (ink/deepsea/jade/celadon)
  user: User;                  // 用户信息
  isAuthenticated: boolean;    // 是否已认证
  sidebarOpen: boolean;        // 侧边栏是否展开
  messages: Message[];         // 当前聊天消息
  chatSessions: ChatSession[]; // 聊天会话列表
  activeSessionId: string;     // 当前激活的会话 ID
  projects: PrototypeVersion[];// 项目列表
}
```

### 4.2 Actions

| Action | Payload | 说明 |
|--------|---------|------|
| `setPage` | `PageType` | 切换当前页面 |
| `setTheme` | `ThemeType` | 切换主题（同时写入 localStorage） |
| `setAuth` | `{ user, isAuthenticated }` | 登录成功时设置用户状态 |
| `clearAuth` | - | 登出时清除用户状态 |
| `addMessage` | `Message` | 添加聊天消息 |
| `setProjects` | `PrototypeVersion[]` | 设置项目列表 |
| `addChatMessage` | `{ sessionId, message }` | 向指定会话添加消息 |
| `setActiveSession` | `string` | 设置当前激活会话 |
| `createChatSession` | `{ id, title }` | 创建新会话 |

### 4.3 持久化

| 数据 | 存储位置 | 读写时机 |
|------|----------|----------|
| `theme` | `localStorage.gewu-theme` | `setTheme` 时写入，初始化时读取 |
| `accessToken` | `localStorage.gewu-access-token` | 登录时写入，401 时清除 |
| `refreshToken` | `localStorage.gewu-refresh-token` | 登录时写入，401 时清除 |
| 用户信息 | `localStorage.gewu-user` (JSON) | 登录时写入，401 时清除 |
| 记住邮箱 | `localStorage.gewu-email` | 勾选"记住我"时写入 |

---

## 5. API 对接分析

### 5.1 代理配置

```javascript
// next.config.mjs
source: '/api/:path*'
destination: 'http://localhost:8081/api/:path*'
```

### 5.2 已对接 API

| 功能 | 端点 | 方法 | 状态 |
|------|------|------|------|
| 登录 | `/api/v1/auth/login` | POST | ✅ 已对接 |
| 注册 | `/api/v1/auth/register` | POST | ✅ 已对接 |
| 刷新令牌 | `/api/v1/auth/refresh` | POST | ✅ 已对接（前端已实现） |
| 登出 | `/api/v1/auth/logout` | POST | ✅ 已对接（前端已实现） |

### 5.3 未对接 API（前端页面有 UI 但无 API 调用）

| 页面 | 后端 API | 状态 |
|------|----------|------|
| 仪表盘 | 无统计 API | ❌ 数据硬编码 |
| 会话 | 无聊天 API | ❌ 模拟数据 |
| 项目管理 | `/api/v1/projects` | ❌ 数据硬编码 |
| 智能体管理 | `/api/v1/agents` | ❌ 数据硬编码 |
| 智能体广场 | `/api/v1/agents/market` | ❌ 数据硬编码 |
| 工作流 | `/api/v1/workflows` | ❌ 数据硬编码 |
| 沙箱 | `/api/v1/sandboxes` | ❌ 数据硬编码 |
| 设置 | 无用户设置 API | ❌ 数据硬编码 |

### 5.4 请求拦截器

```typescript
// 请求拦截器：自动注入 Bearer Token
config.headers.Authorization = `Bearer ${getAccessToken()}`

// 响应拦截器：401 时清除 token 并跳转登录
if (error.response.status === 401) {
  clearTokens();
  window.location.href = '/';
}
```

---

## 6. 设计体系分析

### 6.1 主题系统

| 主题 ID | 名称 | 主色 |
|---------|------|------|
| `ink` | 墨韵·深色东方 | `#00b894` |
| `deepsea` | 深海·Logo原色 | `#0ea5e9` |
| `jade` | 玉蕴·浅色专业 | `#0d9488` |
| `celadon` | 青瓷·科技融合 | `#2ea087` |

### 6.2 颜色语义

| 颜色类 | 用途 |
|--------|------|
| `text-tech-400` | 主色调（青绿）— 激活态、主按钮 |
| `text-cyber-400` | 智能体相关（蓝色） |
| `text-gold-400` | 警告/暂停（金色） |
| `text-cinnabar-400` | 危险/错误/删除（朱红） |
| `text-jade-400` | 成功/已完成（玉色） |
| `text-green-400` | 运行中/成功 |
| `text-ink-100~500` | 文本层级（浅→深） |

### 6.3 组件规范

| 组件 | 说明 |
|------|------|
| `glass-dark` | 深色玻璃态卡片（背景模糊 + 半透明） |
| `btn-primary` | 主按钮（青绿渐变） |
| `card-hover` | 卡片悬停效果 |
| `nav-item active` | 导航激活态 |
| `CustomSelect` | 自定义下拉选择器 |
| `Toast` | 消息提示（success/error/info） |

---

## 7. 隐藏逻辑汇总

### 7.1 导航隐藏逻辑

1. **noAction 项**: `requirements` 和 `usage` 点击无效（可能为预留功能）
2. **底部按钮切换**: 在 `dashboard` 时显示设置按钮，其他页显示返回按钮
3. **Badge 永远显示**: `chat` 的 badge `3` 和 `requirements` 的 badge `12` 是静态的

### 7.2 登录隐藏逻辑

1. **非受控组件**: 使用 `ref` 而非 `state` 管理表单，解决浏览器 autofill 问题
2. **autofill 检测**: 通过 CSS 动画 + `animationstart` 事件检测浏览器自动填充
3. **Token 持久化**: 登录后 Token 存入 localStorage，刷新页面自动恢复登录态
4. **退出不完整**: Header 退出按钮未清除 `gewu-access-token` 和 `gewu-refresh-token`

### 7.3 会话隐藏逻辑

1. **模拟流式输出**: 使用 `useChatStream` hook + setTimeout 模拟打字机效果
2. **思考过程动画**: 4 个思考步骤依次变为 `done` 状态
3. **消息发送限制**: `isStreaming` 时禁止发送新消息
4. **Enter 发送**: `Enter` 键发送，`Shift+Enter` 换行

### 7.4 智能体管理隐藏逻辑

1. **操作菜单条件显示**: 根据 `published` 和 `status` 动态显示不同菜单项
2. **状态转换**: 所有操作仅关闭菜单，无实际状态变更
3. **广场过滤**: 仅显示 `published=true` 的智能体

### 7.5 项目管理隐藏逻辑

1. **只读字段**: 项目编号（自动生成）、状态（默认"立项申请"）、提出人（当前用户）、提出时间（当前日期）
2. **甘特图占位**: 显示"甘特图视图开发中..."
3. **搜索/筛选**: 仅 UI，无实际过滤逻辑

### 7.6 工作流隐藏逻辑

1. **双状态系统**: 设计状态（draft/published）+ 运行状态（stopped/running/paused）
2. **画布视图**: 切换到画布视图显示 `WorkflowCanvas` 组件

---

## 8. 数据字典

### 8.1 枚举类型

```typescript
type PageType = 'login' | 'dashboard' | 'chat' | 'projects' | 'settings' 
  | 'agent-market' | 'agent-manage' | 'my-agents' | 'skill-library' 
  | 'my-skills' | 'prototype' | 'requirements' | 'workflow' 
  | 'usage' | 'sandbox' | 'mcp-server';

type ThemeType = 'ink' | 'deepsea' | 'jade' | 'celadon';

interface User {
  userId?: string;
  username?: string;
  displayName?: string;
  roles?: string[];
  name: string;        // 展示用
  role: string;        // 展示用
  department: string;  // 展示用
  avatar: string;      // 头像首字
}

interface Message {
  id: string;
  role: 'user' | 'ai';
  content: string;
  timestamp: string;
  thinking?: ThinkingStep[];
  thinkingExpanded?: boolean;
}

interface ThinkingStep {
  id: string;
  label: string;
  status: 'pending' | 'active' | 'done';
  duration: string;
}
```

### 8.2 后端 API 响应结构

```typescript
interface Result<T> {
  code: number;       // 10000 = 成功
  message: string;
  data: T;
  timestamp: number;
  success: boolean;
}

interface TokenDTO {
  accessToken: string;
  refreshToken: string;
  tokenType: string;    // "Bearer"
  expiresIn: number;    // 秒
  userId: string;
  username: string;
  displayName: string;
  roles: string[];
}
```

### 8.3 错误码

| Code | 含义 |
|------|------|
| 10000 | 操作成功 |
| 10001 | 系统内部错误 |
| 10002 | 参数校验失败 |
| 11001 | 用户不存在 |
| 11002 | 用户已存在 |
| 11003 | 密码错误 |
| 11004 | 用户已禁用 |
| 11005 | 用户已锁定 |
| 11006 | 令牌已过期 |
| 11007 | 令牌无效 |

---

## 9. 待完善事项

### 9.1 功能缺失

| 优先级 | 事项 | 影响范围 |
|--------|------|----------|
| P0 | 退出登录未清除 token | Header.tsx |
| P0 | 会话页未对接真实流式 API | ChatPage.tsx |
| P1 | 项目管理未对接后端 CRUD | ProjectsPage.tsx |
| P1 | 智能体管理未对接后端 CRUD | AgentManagePage.tsx |
| P1 | 工作流未对接后端 CRUD | WorkflowPage.tsx |
| P1 | 沙箱未对接后端 CRUD | SandboxPage.tsx |
| P2 | 仪表盘无真实统计数据 | DashboardPage.tsx |
| P2 | 设置页无持久化 | SettingsPage.tsx |
| P2 | 需求管理页未实现 | RequirementsPage.tsx |
| P2 | 我的智能体页未实现 | MyAgentsPage.tsx |
| P2 | 技能库/我的技能页未实现 | SkillLibraryPage.tsx |
| P2 | MCP Server 页未实现 | McpServerPage.tsx |
| P3 | 甘特图视图未实现 | ProjectsPage.tsx |
| P3 | SSO 登录未实现 | LoginPage.tsx |
| P3 | 第三方登录未实现 | LoginPage.tsx |
| P3 | 忘记密码未对接 API | LoginPage.tsx |

### 9.2 技术债务

| 事项 | 说明 |
|------|------|
| Card 组件引用 | `SettingsPage` 引用 `@/components/ui/Card` 但未找到定义 |
| ClickableCard 引用 | `DashboardPage` 引用 `@/components/ui/ClickableCard` 但未找到定义 |
| 路由不使用 Next.js Router | 所有页面切换通过 Redux state，URL 不变 |
| 无路由守卫 | 未登录用户可通过修改 Redux state 访问任何页面 |
| 401 跳转路径 | `window.location.href = '/'` 但首页路径可能是 `/login` |

### 9.3 数据硬编码清单

所有页面的数据均为硬编码静态数据，未从后端获取：
- DashboardPage: stats, recentChats, todos
- ChatPage: chatGroups, aiResponses
- ProjectsPage: columns (项目看板数据)
- AgentManagePage: agents (8 个智能体)
- AgentMarketPage: allAgents (12 个智能体)
- WorkflowPage: workflows (6 个工作流)
- SandboxPage: sandboxes (5 个沙箱)
- UsagePage: kpis, modelUsage, topAgents
- SettingsPage: defaultProviders, models

---

> **文档结束** — 共分析 16 个页面、3 个布局组件、4 个工具库、1 个状态管理、20+ 个页面交互逻辑
