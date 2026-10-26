# 工作空间网页终端功能详细设计（xterm.js + PTY）

| | |
|---|---|
| 文档编号 | 44 |
| 状态 | 功能详细设计，待审核后实施 |
| 关联 | 43-web-terminal-scenario-analysis（调研结论：S1/S2/S5/S7/S9 场景，形态 B）、42（同步服务，卷为协作中枢） |
| 决策输入 | 用户已定：按钮"启动沙箱"替换为"打开终端"；前端采用 xterm.js（即形态 B 完整 PTY 交互） |

---

## 1. 目标与非目标

**目标**
- "我的文件"页"启动沙箱"按钮替换为"打开终端"：一键进入用户工作区沙箱的完整交互终端（bash，起始目录 /workspace）
- 终端与 AI 会话、文档同步共享同一持久卷——S1 打包解压 / S2 批量整理 / S5 数据处理 / S7 构建验证 / S9 AI 协作回路直接可用
- 沙箱生命周期全自动：无则建、停则启、断连自动回收，用户零心智负担

**非目标（本期不做）**
- 开发空间页既有简易终端的替换/二合一（保持现状，后续迭代收敛）
- ZMODEM（sz/rz）文件拖传、终端录制回放、多面板分屏、移动端专项优化
- 命令扫描应用于交互流（交互 PTY 无法逐条扫描，见 §6 治理说明）
- pandoc 等工具链固化进镜像（待定项，不阻塞本期）

---

## 2. 总体架构

```
┌─────────────┐  WS(wss)   ┌──────────┐  ws route  ┌──────────────┐  WS+internal-key  ┌──────────────┐
│ WorkspacePage│ ─────────→ │  gateway  │ ─────────→ │ gewu-sandbox  │ ────────────────→ │ docker exec  │
│ xterm.js     │  ticket    │ (SCG 原生 │            │ /ws/pty 端点  │  docker-java      │ TTY 容器      │
│ + FitAddon   │  鉴权握手   │  WS 透传) │            │ PTY 会话注册表 │  withTty attach   │ (dev 沙箱)    │
└─────────────┘            └──────────┘            └──────────────┘                   └──────────────┘
       │                         │                        │
       │  POST /terminal-ticket  │                        │ recordActivity 刷新 lastUsedAt
       └────────→ gewu-interface（校验用户+签发一次性 ticket）│
```

- **通道直连 gewu-sandbox**：终端是纯通道能力，不经 interface 业务层；SCG（Spring Cloud Gateway）原生支持 WS 路由（`uri: ws://localhost:8082`）
- **鉴权用一次性 ticket**：避免长 JWT 进握手 URL。前端先向 interface 申领（校验用户登录态），再携 ticket 建立 WS；sandbox 验 ticket 换 userId
- **沙箱即 dev 沙箱**：复用 `ensureDevSandboxForUser`（探活/惰性创建/自愈），**废弃 source=workspace 独立容器**，坚持"一用户一沙箱"

---

## 3. 功能需求详述

### F1 按钮改造："启动沙箱" → "打开终端"

| 状态判定 | 按钮显示 | 点击行为 |
|---|---|---|
| dev 沙箱不存在或已停止 | `▶ 打开终端` | ensureDevSandboxForUser（自动创建/启动，按钮转 spinner）→ 成功后打开终端面板 |
| dev 沙箱运行中 | `>_ 打开终端`（绿点常亮态） | 直接打开终端面板 |
| 启动中/连接中 | spinner + "连接中…" | 禁点 |
| 连接失败 | 恢复为可点 + 错误 toast（区分：沙箱启动失败 / WS 连接失败） | 重试 |

- 原创建逻辑 `createWorkspaceSandbox` 标记 `@Deprecated`，端点 `POST /api/v1/workspaces/sandboxes` 保留一个版本周期后删除（前端本次摘除调用）
- 面板形态：页内底部抽屉（默认 40% 高度，可拖拽调高/全屏），不弹独立窗口——保持与文件树的上下文对照（S9 回路场景需要同时看文件和终端）

### F2 终端会话生命周期

```
打开面板 → [申领 ticket] → [WS 握手] → [PTY attach] → 交互中 ⇄（断线→自动重连×3）
   ↓ 沙箱停止/容器销毁                                     ↓
 提示"沙箱已回收，点击重启" ──────────────────────→ 主动关闭/页面离开 → [WS 关闭] → [exec 释放]
```

- **会话粒度**：一个 WS 连接 = 一个 PTY 会话 = 一次 `docker exec`（TTY）；允许同沙箱多标签页，**并发上限 3 会话/用户**（超出拒绝并提示）
- **重连**：网络闪断自动重连（指数退避 1s/2s/4s，共 3 次）；PTY 不做服务端会话保持——重连成功后开**新会话**并提示"终端会话已重建"（前台进程丢失是 docker exec 语义决定，如实告知用户）
- **沙箱回收联动**：终端打开期间每次心跳刷新沙箱 `lastUsedAt`（复用 `recordFileActivity` 同款机制），正常使用不会触发 120 分钟空闲回收；若容器仍被外部销毁/停止，服务端推 `control: sandbox_down` 帧，前端提示并可一键重启（走 F1 ensure 链路）
- **空闲治理**：PTY 会话 30 分钟无流量服务端主动关闭（先推警告帧）；关闭后容器仍按既有空闲策略回收

### F3 PTY 数据帧协议（WS 消息，JSON 文本帧 + 二进制帧混排）

| 方向 | 类型 | 载荷 | 说明 |
|---|---|---|---|
| C→S | `control` | `{"type":"init","ticket":"...","cols":120,"rows":32}` | 握手后首帧：ticket + 初始尺寸 |
| S→C | `control` | `{"type":"ready","sessionId":"pty-xxx"}` | attach 成功 |
| S→C | `control` | `{"type":"closed","reason":"sandbox_down"}` | 沙箱停止/exec 退出 |
| S→C | `control` | `{"type":"idle_warning","remainSec":60}` | 空闲预警 |
| C→S | 二进制 | 原始键盘输入字节流 | 直写 PTY stdin |
| S→C | 二进制 | 原始终端输出字节流（UTF-8，含 ANSI 转义序列） | xterm.js 直接 write |
| C→S | `control` | `{"type":"resize","cols":w,"rows":h}` | FitAddon 尺寸变化时发送 |

- 会话建立后**终端数据走二进制帧**（零解析开销、二进制安全），控制帧走文本 JSON
- 服务端按 `sessionId` 注册表管理：`ConcurrentHashMap<ptySessionId, PtySession>`（含 execId、WS session、最后活跃时间）

### F4 沙箱保活与统计联动

- PTY 打开/心跳（30s）/正常关闭 → 刷新 `lastUsedAt`（`SandboxService.recordActivity`，与文件操作同款）
- 连接/断开写审计（`logAudit(sandboxId, "PTY_OPEN"/"PTY_CLOSE")`）；**不记录键盘内容**（敏感，仅会话元数据）

### F5 认证与安全

| 环节 | 机制 |
|---|---|
| ticket 申领 | `POST /api/v1/workspaces/terminal-ticket`（interface 层）：校验 JWT 登录态 → 生成一次性 ticket（TTL 60s、单次使用，存 Redis 或内存）→ 返回 |
| WS 握手 | gateway 透传至 sandbox `/ws/pty`；sandbox 验 ticket（一次性消费）绑定 userId；失败关闭码 4401 |
| 隔离边界 | 每用户独立容器+独立卷（既有），终端只能触达自己的 /workspace；rootfs 现状可写（与 dev 沙箱一致），镜像层改动随容器销毁 |
| 命令扫描 | **交互 PTY 不适用逐条扫描**——治理依据：用户操作的是自有容器自有卷，信任级等同开发空间 `/exec`（该通道同样不扫描）；agent 工具链的扫描（AI 生成代码路径）保持不变 |
| 资源 | 沿用容器既有 CPU/内存/PidsLimit；PTY 会话 3 个/用户、30 分钟空闲关闭；WS 消息无大小风险（输入为字节流，服务端不落盘） |

### F6 交互细节（前端）

- **渲染**：xterm.js（`@xterm/xterm`）+ FitAddon（容器尺寸自适应，ResizeObserver 防抖 100ms）+ WebLinksAddon；字体沿用平台等宽栈（JetBrains Mono 优先，回退系统 mono）；主题使用现有设计令牌（背景 rgba(8,18,17,.92)、前景 ink-100、光标 tech-400）
- **复制粘贴**：Ctrl+Shift+C/V 与选区右键菜单；粘贴多行前提示确认（防误粘贴危险命令——xterm 无回车缓冲，多行粘贴即多行执行）
- **中文输入**：xterm.js 原生 IME 组合支持，无需额外处理；验证用例包含中文路径/中文内容 echo
- **滚动**：scrollback 5000 行；Ctrl+L 清屏走 PTY 本义
- **可见性**：页面切后台连接保持（SSE 同款策略）；离开"我的文件"页关闭面板即断开 PTY（容器继续按空闲策略运行）

---

## 4. 技术设计

### 4.1 gewu-sandbox（核心改动，约 2.5~3 人日）

1. 依赖：新增 `spring-boot-starter-websocket`
2. `TerminalTicketService`：ticket 签发/校验（内存 TTL map 即可，进程内一次性；Redis 为可选增强）
3. `TerminalWsHandler`（`/ws/pty`，`TextWebSocketHandler` + `BinaryWebSocketHandler` 混合）：
   - 握手拦截器：验 ticket → 绑定 userId → 拒绝匿名（4401）
   - `init` 帧：`ensureDevSandboxForUser(userId)`（复用 DevWorkspaceService 逻辑经内部调用或 SandboxService 内部等价方法）→ `execCreateCmd`（`bash -i`，`withTty(true).withAttachStdin/Stdout/Stderr(true)`）→ `execStartCmd` 启动
   - 输出泵：`ExecStartResultCallback` onNext(Frame) → WS 二进制帧（异步背压：`stream(1)` 逐帧拉取，防大输出打爆 WS 缓冲）
   - 输入泵：WS 二进制帧 → PipedOutputStream（`withStdIn`）
   - resize：docker-java `resizeExecCmd(execId, h, w)`
   - 退出：callback onComplete → 推 `closed` 帧 → 清注册表
4. `PtySessionRegistry`：会话表 + 每用户并发计数 + 空闲扫描（@Scheduled 60s，超 30 分钟无流量关闭）
5. `recordActivity(sandboxId)`：PTY 生命周期事件刷新 lastUsedAt
6. 配置：`gewu.sandbox.terminal.{enabled:true,max-sessions-per-user:3,idle-timeout-minutes:30}`

### 4.2 网关（0.5 人日）

- 路由：`/api/v1/sandboxes/**` 的 uri 由 `http://localhost:8082` 改为 `ws://localhost:8082`（SCG 对同一路径同时透传 HTTP 与 WS 升级）
- 补 WS 相关超时配置（`httpclient.ws-idle-timeout` ≥ 会话心跳周期）

### 4.3 gewu-interface（0.5 人日）

- `POST /api/v1/workspaces/terminal-ticket`：鉴权后调 sandbox 内部 API 签发 ticket（SandboxClient 增加对应方法），返回 `{ticket, wsUrl}`
- `createWorkspaceSandbox` 标记 `@Deprecated`（保留实现一个版本）

### 4.4 前端（2~2.5 人日）

1. 依赖：`@xterm/xterm @xterm/addon-fit @xterm/addon-web-links`
2. `lib/terminal.ts`：ticket 申领、WS 连接管理（状态机：idle/connecting/ready/closed）、自动重连、心跳
3. `components/ui/TerminalPanel.tsx`：xterm 封装（主题令牌化、Fit、resize 上报、输入泵、断线提示条与"重启沙箱"按钮）
4. `WorkspacePage.tsx`：按钮替换（F1 状态机）+ 底部抽屉布局（拖拽调高/全屏切换）

---

## 5. 分期与工作量

| 阶段 | 内容 | 工作量 |
|---|---|---|
| M1（本期） | sandbox PTY 通道 + ticket 鉴权 + 网关路由 + 前端终端面板 + 按钮收编 | **6~7 人日** |
| M2（按需） | 工具链固化（pandoc 入镜像）、开发空间简易终端收敛为同组件、命令片段快捷栏 | 2~3 人日 |
| M3（远期） | ZMODEM 拖传、会话录制回放 | 另评估 |

## 6. 验收清单

1. 冷启动：无沙箱状态点"打开终端"→ 自动创建 → 3s 内出现 shell 提示符，pwd=/workspace
2. 卷可见性：终端 `ls` 能看到 AI 会话写入的文件；AI 会话 read_file 能读到终端创建的文档（同一卷）
3. 交互完整：vim 打开编辑保存、Ctrl+C 中断前台进程、中文输入输出、tab 补全、滚动回看
4. resize：拖拽抽屉高度后 `stty size` 与实际一致、全屏不乱码
5. 回收联动：终端闲置 30 分钟自动关闭并收警告帧；容器外部销毁时终端收到 sandbox_down 且可一键重启
6. 并发与限额：第 4 个标签被拒；401 过期 ticket 握手被拒
7. 安全：无 ticket 直连 WS 被拒；ticket 二次使用被拒；审计出现 PTY_OPEN/PTY_CLOSE
8. 兼容：原"启动沙箱"API 调用方（无）不受影响；HTTP 文件接口与 WS 并存正常

## 7. 风险与开放问题

| 风险 | 缓解 |
|---|---|
| docker-java PTY attach 的背压与线程模型 | 输出泵用 `stream(1)` 逐帧拉取 + 独立单线程 executor；M1 联调重点验证大输出（cat 大文件）不丢帧不阻塞 |
| SCG ws:// 路由与现有 http 路由共存 | SCG 原生支持；联调首日验证，失败则降级方案=interface 层加 WS 反向代理（+1 人日） |
| rootfs 可写 + 网络开启的滥用面 | 沿用 dev 沙箱既有策略（网络可独立关闭配置）；本期不新增限制，M2 评估只读 rootfs + 白名单挂载 |
| PTY 无命令扫描的治理口径 | 已在 §F5 说明信任级依据；如需管控，M2 可加"首条命令黑名单提示" |
| 旧 source=workspace 沙箱残留 | 废弃 API 一个版本后清理；调度器照常回收存量容器 |
