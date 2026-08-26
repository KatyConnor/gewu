# Sprint 3（编排补齐与功能增强）完成报告

> **日期**：2026-08-26 | **执行依据**：`docs/plan/exe_plan/optimization-implementation-plan-2026-08.md` Sprint 3
> **计划工期**：4 周 | **实际**：2 个工作会话（AI 辅助编码节奏）
> **验收结论**：✅ 四项任务全部交付，全量 333 个测试绿（engine 186 / application 123 / interface 24 含 E2E 与性能基准），DB 迁移推进至 V35

## 交付清单（5 个提交）

| 提交 | 任务 | 核心交付 |
|------|------|---------|
| `2b13512` | T3.1 编排补齐 | Pipeline 重构为边驱动图遍历器：TOOL（模板渲染+安全管线）/ROUTER（受限条件表达式）/PARALLEL（并发扇出）/MERGE（汇聚等待+json_merge）；ExecutionControl 协作式暂停/取消+断点检查点；OrchestrationEngine.pause/resume/cancel 真实实现+resume SSE 端点；15 个事件常量提取 |
| `a4564df` | T3.2 MCP 升级 | StreamableHttpClient（2025-03-26 规范：JSON/SSE 双响应、Mcp-Session-Id 会话、initialized 握手、DELETE 终止）；stdio 补 initialized 通知；SseMcpClient @Deprecated+删死字段；Manager 注入 ObjectMapper（D-14） |
| `00fca51` | T3.3 会话增值 | 标题自动生成（异步+清洗+防覆盖）；消息重新生成（SSE，逻辑删除+原输入重跑）；归档/取消归档；分享（slug 幂等+免鉴权脱敏读取+网关/Security 双放行）；置顶（V34+列表排序） |
| `eae48d3` | T3.4 A/B 实验框架 | V35 分组列+执行账本自动落库（提前兑现 T4.1 核心）+对比报表 API+四组预置对照 |

## 过程中发现并修复的缺陷（4 个）

1. **扇出路径计数死锁**：PARALLEL 节点扇出后自身路径未结束，activePaths 永不归零导致 collectList().block() 无限挂起（测试先行暴露）；
2. **暂停信号残留**：PAUSE 信号生效后未消费，断点恢复后首个节点再次读到旧信号形成无限暂停循环（ExecutionControl 增加 clearSignal）；
3. **bash case 模式引号语义**：模拟 MCP 服务器的 `*"method":"x"*` 模式中双引号是语法引号而非字面量，导致任何请求不命中、stdio 客户端永久阻塞（改为通知分支置前的无引号模式）；
4. **测试 schema 漂移**：H2 测试库 schema.sql 未同步 V34/V35 列（pinned/experiment_group），E2E 报 Column not found（已补齐；此类漂移在 V33 时已发生过一次，建议后续将测试 schema 生成改为从 Flyway 迁移自动派生）。

## 验收数据对照

| 计划验收项 | 结果 |
|-----------|------|
| 四类节点单测 | ✅ TOOL/ROUTER/PARALLEL/MERGE 共 24 个用例（含 json_merge、乱序声明、非环不误报） |
| pause->resume 断点续跑集成测试 | ✅ 暂停-恢复全闭环用例（PAUSED 收尾->resume 跳过已完成节点->SUCCESS，检查点消费断言） |
| 三种 MCP 传输单测 | ✅ stdio（bash 模拟）/streamable_http（HttpServer 模拟 JSON+SSE）/sse（保留兼容） |
| 会话四功能 E2E | ✅ 后端全链路+单测 17 个（生命周期 7+标题 5+幂等/脱敏/非成员拒绝）；前端按钮接线留待下迭代（诚实记录：标题生成对前端零改造，重发/置顶/分享需 ChatPage 增加对应入口） |
| 实验四组对比报表 | ✅ API+聚合查询+单测；对照组样例（modelConfig.experimentGroup）已随服务暴露 |

## 与计划的偏差（如实记录）

1. **前端联调未完成**（T3.3 验收项之一）：后端 API 与测试完备，ChatPage 的重发/置顶/分享按钮需前端迭代（下次会话补齐，预计 0.5 人日）；
2. **V26 表状态机未接入编排引擎恢复**：resume 走内存检查点（进程重启丢失，需从 graph_snapshot 重放），已在 ExecutionControl Javadoc 明确标注该限制与应用层重建路径；
3. T4.1 的执行账本核心（自动落库+token 回写）已随 T3.4 提前交付，S4 剩余工作聚焦成本单价与看板。

## 累计技术债清理进度

D-4（编排存根）✅ / D-7（MCP）✅ / D-13（事件常量）✅ / D-14（ObjectMapper）✅ / D-18（会话增值）✅ —— Sprint 3 关闭 5 项；R5（编排能力宣称不符）✅ 缓解。

## Sprint 4 预告（收敛与可观测闭环）

T4.1 成本回填收尾（model_provider 单价+session cost 字段+UsagePage 真数据）-> T4.2 OTel 默认开+告警规则 -> T4.3 双轨收敛决策门（依据 T3.4 实验数据）-> T4.4 死代码清理 -> T4.5 事件协议完善 -> T4.6 认知层接线/裁剪决策。
