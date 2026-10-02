# Phase 2 完成报告：gewu-desktop（契约测试 + 管理面 + 沙箱 + 会话页对齐）

> **文档编号**：DONE-DESKTOP-P2 | **日期**：2026-10-02 | **状态**：✅ Phase 2 完成
> **基线**：架构设计 V3.1（docs/design/42）+ 实施计划 V1.2（docs/plan/exe_plan）+ Phase 1 报告（DONE-DESKTOP-P1）
> **提交区间**：`d53860a..68c88e8`（17 个提交，未 push）

---

## 一、交付范围对照（计划 → 实际）

| 计划任务 | 状态 | 实证（提交） |
|----------|------|--------------|
| D1-13 契约测试 | ✅ | DesktopProfileContractTest **10 例**（@SpringBootTest+stub）：chat 流事件形状/会话 CRUD/归档过滤/幂等重放/file-changes/models/engine-admin 信封断言（21ac6c3） |
| D2-1/3 Docker 沙箱执行器 + code_execute | ✅ | LocalDockerSandboxExecutor（容器名 hash 定址 / --memory 512m --cpus 1 --network none / 宿主 uid:gid 注入 / inspect 幂等建容器）+ CodeExecuteTool，**4 测试**（本机无 Docker 时 assumeTrue 自动跳过）（567d6a4） |
| D2-10 记忆 v1 | ✅ | DesktopMemoryStore TF-IDF 检索（CJK bigram 分词）+ MemoryRouter system 消息前置注入，**4 测试**（21ac6c3） |
| D2-2 MCP 服务器管理 | ✅ | DesktopMcpConfigSource（local_mcp_server 表 → McpServerDescriptor）+ `/mcp-servers` CRUD + 工具动态发现端点（1e79713）+ 管理面板「MCP」标签 |
| D2-8 Agent 管理 | ✅ | `/agents` CRUD 端点（db5437f）+ 管理面板「Agent」标签（创建即生效于对话） |
| D2-9 本地技能库 | ✅ | `/skills` 安装/清单/内容/卸载 → 工作区 `.gewu/skills/*.md`，project.context 装载进对话（364f0c7）+ 管理面板「技能」标签 |
| D2-6 编排生命周期 | ✅ 端点 | `/orchestration` execute/stream + pause/resume/cancel/pausable（5ce5319）；**画布 UI 移交 Phase 3** |
| D1-25 终端面板 | ✅ | xterm.js + 双层 PTY（node-pty 优先 / `script -qec` 回退）（bc4ae0f） |
| Result 信封统一 | ✅ | DesktopResultAdvice 全局 `{code:10000,...}` 包装（豁免 SseEmitter/String/byte[]）+ String 场景修复（21ac6c3/74dd949） |
| 会话页对齐 web（用户差异清单） | ✅ | 顶栏信息带（📁workspace / ⎇Git 分支 / 文件变更 +N/-N）+ 尾部收尾行（轮次/用时/tokens）+ 回合汇总条（文件行统计/审查/撤销）+ 时间线持久化还原（a67123a/61d8f25） |
| **超出计划增量** | ✅ | ① 管理面板四标签 CRUD UI（68c88e8）② SourceEditor 零依赖编辑器（54d6a0e）③ 引擎孤儿三层根治（cd1c105）④ 设置面板侧栏浮层（3619814） |

## 二、实测验证记录（用户真机 + 自动化）

| 验证项 | 结果 |
|--------|------|
| 自动化测试 **18 例全绿**（契约 10 + 记忆 4 + 沙箱 4；沙箱用例依赖本机 Docker，缺席自动跳过） | ✅ mvn test |
| stub 两阶段工具循环 → 视觉回归时间线图文交错 | ✅ 自动化（b9139ee，GEWU_VISUAL_TEST） |
| 撤销全链路 E2E（变更列表 → undo → before 快照回写 + 列表清空） | ✅ 自动化 |
| git-info 返回真实分支（fix/sandbox-security-storage） | ✅ 自动化 |
| 每批次构建全绿（typecheck 0 错 / vite built / JAR packaged） | ✅ 自动化 |
| Monaco『打开』卡死三连取证 → 修复后用户复测通过 | ✅ 用户真机（eae3e3a→54d6a0e） |
| 引擎 degraded 复发 → 三层根治后不再复发 | ✅ 用户真机（cd1c105） |
| 会话页差异清单逐项补齐（信息带/收尾行/汇总条/撤销） | ✅ 用户真机（a67123a） |

## 三、过程中发现并修复的缺陷（全部有用户实证或自动化回归）

1. **Monaco paths 模式桌面不可用**（vite 对 `monaco-editor/esm/vs/...` 深路径 `?worker` 解析缺陷）→ npm 双载体（`loader.config({monaco})`）
2. **loader.init 异步竞态白屏** → `src/monaco-setup.ts` 同步首行预配置（先于任何 React 渲染）
3. **『打开』主线程阻塞整页卡死**（Monaco 大文件/worker 链路）→ SourceEditor 降级路线：prism 高亮只读 + textarea 编辑，零 loader/零 worker（54d6a0e）
4. **String 返回值 ISO-859-1 中文损坏（0xa5）**：StringHttpMessageConverter 兜底写出 → 控制器显式声明 `Result<String>`（74dd949）
5. **孤儿 JVM 占用 H2 锁致 degraded 复发** → 双向死亡联动：Electron `process.on('exit') killSync` + 引擎 stdin 管道看门狗（EOF 自退出）+ 视觉模式隔离 userData（cd1c105）
6. **设置面板展开撑破布局** → 收敛为侧栏内浮层（3619814）
7. **过程时间线折叠不可开/刷新即失** → content 喂 tracker + expanded 状态 + 过程回写端点持久化（61d8f25）

## 四、遗留与移交

| 项 | 去向 |
|----|------|
| 编排工作台 UI（画布复用 + pause/resume 交互） | Phase 3 首批（端点已就绪） |
| 协同基础 D2-11+（ProxyLlmProvider 已实现；DesktopSync 依赖平台侧 N1 LLM 代理 / N2 会话同步端点） | 待平台侧排期确认后启动 |
| Monaco worker 优化（vite exports 缺陷规避后复评估） | Phase 4 backlog |
| Playwright-Electron 视觉回归常态化 | Phase 4 backlog |
| loongarch 信创包 / 审计上送 / 策略下发 | Phase 3/4 |

## 五、结论

**Phase 2 目标达成**：单机形态补齐管理面（模型供应商/Agent/MCP/技能四标签 CRUD）、隔离沙箱执行（code_execute）、记忆 v1（TF-IDF）、契约测试（18 例自动化全绿）、终端面板；会话页按用户实测差异清单与 Web 端对齐（信息带/收尾行/汇总条/撤销/时间线持久化）。**平台侧零代码侵入原则保持不变**（仅 monorepo 注册与共享包协议复用）。

**下一步（Phase 3）**：① 编排工作台 UI（DesktopOrchestrationController 端点已就绪，画布 + 生命周期交互）② 协同基础（等平台 N1/N2 排期）③ Phase 4 backlog 按优先级滚动。
