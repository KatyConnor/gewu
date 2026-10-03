# Phase 3 首批完成报告：gewu-desktop（编排工作台 + 用户实测反馈五连修）

> **文档编号**：DONE-DESKTOP-P3-B1 | **日期**：2026-10-03 | **状态**：✅ 首批完成（用户一阶段验收通过）
> **基线**：架构设计 V3.1（docs/design/42）+ Phase 2 报告（DONE-DESKTOP-P2）
> **提交区间**：`6acdc5a..ebb6205`（10 个提交，分支 fix/sandbox-security-storage，未 push）

---

## 一、交付范围对照

| 交付项 | 状态 | 实证（提交） |
|--------|------|--------------|
| Phase 2 报告归档 + 测试计数勘误（20→18，@TestMethodOrder 误计） | ✅ | 6acdc5a |
| **编排工作台 UI**（D2-6 渲染层） | ✅ | 46fa0e7 |
| 侧栏常驻导航（主页/对话/编排三视图） | ✅ | bf531a3 |
| 侧栏形态体系（主页独立形态 + 裁剪根修 + 引擎数据目录隔离） | ✅ | c6b7fa7 |
| 侧栏统一 footer（三视图同序同构）+ 编排去会话列表 | ✅ | cdef73c |
| 终端可用化（挂载缺失根修 + 早期输出竞态） | ✅ | eb9ae86 |
| 终端键盘输入接线（term.onData → PTY） | ✅ | 07deae2 |
| 审查分栏布局打乱根修（flex 行 + 溢出防线） | ✅ | ebb6205 |

**编排工作台能力**：四模式模板库（PIPELINE/SUPERVISOR/DEBATE/SWARM，引擎可直跑）、DAG 画布
（节点状态实时着色 + 产出预览 + 连线高亮）、节点编辑（角色/指令模板/Agent 绑定）+ JSON 直编、
运行控制台（逐事件）、生命周期（暂停协作式/恢复/取消，检查点内存态 UI 明示）。
**引擎侧补齐**：ExecuteRequest 增补运行目标 input + 上下文变量播种（与平台
OrchestrationService#buildContextVariables 同构——PIPELINE 起始输入/DEBATE 辩题/模型兜底变量由此生效）。

## 二、自动化验证

| 验证项 | 结果 |
|--------|------|
| 编排契约测试 orchestrationStreamShape（stub 全链路真实引擎 PIPELINE 两节点，断言事件序列 + 播种语义） | ✅ 首跑通过 |
| 全套测试 **19 例全绿**（契约 11 + 记忆 4 + 沙箱 4） | ✅ mvn test |
| 每批次 typecheck 0 错 / vite built / JAR packaged | ✅ |
| 视觉自捕获：三视图侧栏形态、终端开启态、审查→分栏路径 + 滚动约束断言 | ✅ 多轮闭环 |

## 三、用户实测反馈缺陷修复清单（全部有真机截图实证 + 复现路径自捕获）

1. **侧栏无常驻导航**——入口只藏主页卡片，进会话即失联（Phase 1 同诉求复现）→ 三视图常驻导航 + 高亮跟随（bf531a3）
2. **主页仍是会话页形态 / 底部管理面板·设置被裁剪**——SessionSidebar 根 `h-full+flex-shrink-0` 在桌面 flex 列侧栏占满视口挤出后续元素；主页侧栏改独立形态（c6b7fa7）
3. **主页与编排 footer 顺序不一致 / 编排页显示会话**——统一 footer 组件 + 编排独立形态（cdef73c）
4. **点终端无视图**——组件从未挂载（JSX 重构事故同类）+ 提示符早期输出竞态（订阅前置 + 缓冲回放）（eb9ae86）
5. **终端无法输入**——xterm→PTY 输入方向从未接线（07deae2）
6. **审查分栏打乱整页且不可关闭**——分栏容器缺 flex 行布局编辑器无限撑高 + `body hidden` 不约束 `html` 视口滚动（ebb6205）

## 四、取证方法论沉淀（本轮新增）

- **headless capturePage 合成层不含 DOM 文本**：截图空白 ≠ 渲染缺陷——取证应逐层实测
  （数据流探针 → xterm 解析缓冲 → DOM 几何/样式），本轮据此避免误判终端渲染问题；
- **视觉钩子扩展**：编排视图/终端开启态/审查分栏捕获点、滚动约束断言、console-message
  视觉模式全量转发（此前 info 级被滤导致插桩不可见）；
- **引擎数据目录隔离**：引擎只认 CLI `--gewu.desktop.data-dir`，Supervisor 此前仅传 env
  （引擎不读）→ 同 cwd 双实例争 H2 锁即 degraded；视觉模式现传隔离 CLI 参数。

## 五、遗留与移交

| 项 | 去向 |
|----|------|
| **打包分发验证**（electron-builder 安装包） | 下一批首选——打包态将暴露 resources/JAR/二进制/原生模块问题，并顺带根治「数据目录随 cwd 漂移」（打包态用户从任意目录启动必踩，需迁移策略） |
| node-pty 原生构建（现 script 回退，首包延迟 ~3s） | 打包批次一并处理（onlyBuiltDependencies 已预留） |
| 协同基础 D2-11+（ProxyLlmProvider 已就绪；DesktopSync 依赖平台 N1/N2 端点） | 待平台侧排期确认 |
| 编排画布深化（拖拽连线/子图）、Monaco worker、Playwright-Electron 常态化 | Phase 3/4 backlog |
| 分支 push/合并 | 20+ 提交在 fix/sandbox-security-storage，时点由用户定 |

## 六、结论

**Phase 3 首批目标达成**：编排域端点全量接上渲染层（四模式模板即可跑、生命周期可暂停恢复），
单机形态的导航/侧栏/终端/文件审查四大交互面按用户实测清单全部闭环；自动化 19 例全绿，
平台侧零代码侵入原则保持。**下一步：打包分发验证批**（安装包产出 + 打包态特有问题根治）。
