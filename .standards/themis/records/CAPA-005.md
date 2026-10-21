# CAPA 整改方案（Corrective Action Plan）— THEMIS-STD-014
id: CAPA-005
rpt_ref: RPT-005
created_at: 2026-10-21T22:35:00+08:00
owner: 产出方（ZCode Agent·SDLC 架构与实施）
state: closed           # 全部整改完成 + 原评审代理 recheck 通过（RPT-005 放行）；残余 S4 已清或登记

| # | 发现 | 整改措施 | 位置 | 截止 | 验证方式 | 状态 |
|---|---|---|---|---|---|---|
| 1 | F-501(S2) | ToolContext：nestedEntriesServed → ConcurrentHashMap.newKeySet()、nestedEntryProbeCache → ConcurrentHashMap（零行为变更）；新增并发回归用例（40 并发 append/20 目录：无异常、去重集完整、探针缓存 40 键完整） | ToolContext.java + 并发测试 | 2026-10-21 | 复审 | done |
| 2 | F-503(S3) | invalidateNestedProbeCache 改包私有可见 + 直测（失效移除/非入口文件 no-op/写→失效→append 即时发现链）；executeFileTool 级用例以直测覆盖核心路径（三处接线为一行胶水） | ReactAgentExecutor + 测试 | 2026-10-21 | 复审 | done |
| 3 | F-502(S3) | 设计 57 §6 补记"引擎本地 EntrySecretScan 最小实现"及双源同步维护理由；common 侧 SecretScanUtilTest 增一致性守护对照（正则集逐字比对断言，测试内联双表） | 设计57/测试 | 2026-10-21 | 复审 | done |
| 4 | F-505(S4) | 设计 57 §4 注明"轮=会话轮次（新任务新建 ToolContext）；任务内多 LLM 轮不重复" | 设计57 §4 | 2026-10-21 | 复审 | done |
| 5 | F-504(S4) | 接线处探测/去重改传 SPI 归一化路径（appendNestedEntry 内部先行 normalize） | ReactAgentExecutor | 2026-10-21 | 复审 | done |
| 6 | F-507(S4) | NestedEntryFinder.find javadoc 明示显式 root 语义（targetPath 须含 root 前缀） | NestedEntryFinder | 2026-10-21 | 复审 | done |
| 7 | F-506(S4) | 58 号 P3-2 落地时引擎同步补 @global/ 跳过——已在 58 号设计 §5.2 引擎行登记（外部跟踪，本次不整改） | 58 号跟踪 | — | 58 P3-2 实施时核对 | 外部跟踪 |

复审安排：整改完成后原评审代理 recheck，通过后 RPT-005 → closed。
