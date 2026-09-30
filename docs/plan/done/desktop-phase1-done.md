# Phase 1 完成报告：gewu-desktop 桌面端（可用版本地 Agent）

> **文档编号**：DONE-DESKTOP-P1 | **日期**：2026-10-01 | **状态**：✅ Phase 1 完成
> **基线**：架构设计 V3.1（docs/design/42）+ 实施计划 V1.2（docs/plan/exe_plan）
> **提交区间**：`776bf4e..HEAD`（六连检查点 + 修复序列，未 push）

---

## 一、交付范围对照（计划 → 实际）

| 计划任务 | 状态 | 实证 |
|----------|------|------|
| D1-1~5 Electron 壳/Supervisor/SecretVault/preload | ✅ | spawn jlink jar/env 注入令牌与 Key/健康轮询/崩溃重启≤3；safeStorage 密文 |
| D1-8~12 desktop-runtime | ✅ | 引擎嵌入（NoOp→桌面 SPI 覆盖）/H2 八表/Profile 端点（chat 落库/sessions CRUD+归档置顶/file-changes/models/engine-admin） |
| D1-14~19 本地工具 | ✅ | 内置文件×4（FileWorkspaceSpi）+ shell.exec（黑名单16条）/git×3/fs.glob/fs.grep/project.context；注册端点实证 7 工具 |
| D1-20~22 权限/审批 | ✅ | 三级权限模式 + ApprovalBroker 弹窗流（diff 双栏/始终允许白名单/超时拒绝） |
| D1-23 变更追踪 | ✅ | local_file_change（before 快照不可变）+ list/diff/content 端点（FileEditorPanel 同构） |
| D1-24~26 渲染层 | ✅ | 共享组件族（时间线/侧栏/PlanCard/FileEditorPanel+Monaco 分包）+ 主题层 + 主页空态 |
| D1-13 契约测试 | ⏳ 移交 Phase 2 开头 | 共享 fixture 双端回放 |
| D1-25~28 终端面板等深化 | ⏳ 移交 Phase 2 | xterm/node-pty |

**超出计划的增量**：视觉回归自捕获模式（GEWU_VISUAL_TEST）、引擎启动分层超时+C1 提速、
供应商删除/重注册恢复、模型名三级解析兜底（firstModelName）。

## 二、实测验证记录（用户真机 + 自动化）

| 验证项 | 结果 |
|--------|------|
| 真实智谱 API 对话（流式/Markdown） | ✅ 用户真机 |
| 授权弹窗 → 允许 → 文件落地 → 变更 chip | ✅ 用户真机（SM4 工具类 +390 行案例） |
| PlanCard 任务卡片（进程 3/4 勾选态） | ✅ 用户真机 |
| 视觉回归四态截图（主页/工作台/会话chip/文件面板） | ✅ 自动化（本轮） |
| 引擎令牌强制（401/200）、Supervisor 集成测试 | ✅ 自动化 |
| stub profile 全链路（幂等重放/归档过滤） | ✅ 自动化 |

## 三、过程中发现并修复的缺陷（全部有用户实证或自动化回归）

1. 引擎 D-2/D-10/D-3 由平台修复，桌面侧完成三厂商回归之 zhipu（D-2 验证：多轮工具调用正常）
2. CORS 预检被令牌过滤器拦截（Failed to fetch）→ 过滤器层自应答 + CorsFilter 升序
3. 401 无 CORS 头（令牌失效被掩盖为网络错误）→ CorsFilter 先于令牌过滤器
4. 渲染层困死主页（新建会话不切换视图）→ view 状态
5. CSS 变量循环引用白线 / 裸 border-r 默认灰白线 → 主题单一定义 + 全局边框覆盖
6. FileEditorPanel 占满全屏（纵列 h-full）→ 横向并列布局
7. composer 结构错位（重构手术失误）→ 归位聊天列
8. 时间线空壳（content 未喂 tracker）+ 展开态写死（折叠永不可开）
9. Supervisor 30s 超时误判 degraded（高负载启动 22~32s）→ 分层超时 45s/120s + C1
10. 供应商删除后 Agent 挂死（无回退）+ 重注册不恢复 enabled

## 四、遗留与移交

| 项 | 去向 |
|----|------|
| D1-13 契约测试（Profile 双端 fixture） | Phase 2 开头 |
| 终端面板（xterm+node-pty）/ D1-25~28 深化 | Phase 2 |
| 工作区选择器 UI（引擎已支持会话级 workspace） | Phase 2 |
| 桌面端视觉回归常态化（Playwright-Electron） | Phase 4 backlog |
| loongarch 信创包 / 审计上送 / 策略下发 | Phase 3/4 |

## 五、结论

**Phase 1 目标达成**：单机形态完整可用——本地对话（流式+图文交错时间线+任务卡片）、
11 工具（含本地文件读写+变更追踪+授权弹窗）、会话管理（归档/置顶）、模型双源管理（密钥
不出本机）、视觉与 Web 端对齐。平台侧零代码侵入（仅 monorepo 注册一行 + 协议 DTO 复用）。

**下一步（Phase 2）**：① D1-13 契约测试 ② 会话双向同步（DesktopSync，依赖平台 N2）
③ 记忆 v1（TF-IDF）④ 配额预检 ⑤ 编排生命周期 UI ⑥ 终端面板。
