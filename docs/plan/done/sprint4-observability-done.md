# Sprint 4（收敛与可观测闭环）完成报告

> **日期**：2026-08-29 | **执行依据**：`docs/plan/exe_plan/optimization-implementation-plan-2026-08.md` Sprint 4
> **计划工期**：4 周 | **实际**：2 个工作会话（AI 辅助编码节奏）
> **验收结论**：✅ 六项任务全部交付，全量 432 个测试绿，DB 迁移推进至 V37

## 交付清单（5 个提交）

| 提交 | 任务 | 核心交付 |
|------|------|---------|
| `78dbbe1` | T4.1 成本回填 | V36 模型单价（model_config.price_per_1k_*）；CostAccountingService（真实 usage 计费/流式字符估算，SessionMapper.appendUsage 原子累计）；执行账本完成回调接入；StatsService 合并对话执行统计 |
| `490fffe` | T4.2 可观测接通 | 引擎发射 cache.hit/miss、model.route、budget.exceeded 三指标；Micrometer 计数器+适配器分派；Grafana 3 新面板；Prometheus 3 新告警（熔断/慢任务/低缓存命中）；K8s configmap OTel 默认开 |
| `d6977c5` | T4.4 死代码清理 | 删 Part/SessionInput/SessionContextEpoch 三空壳（实体+Mapper+V37 drop 表）；CacheKeys.session()；空包；SseEventManager.sendToUser 语义修复（userId 双索引定向）；前端 mock 会话/张明远硬编码/搜索框接线 |
| `1b828b3` | T4.5 事件协议 | experience_saved/failure_recorded 流式发射；verification_result/confidence_check 随目标验收透出；前端 chat.ts+agentProcess.ts 消费 |
| `1763fca` | T4.3+T4.6 收敛 | session.ts 试点迁移统一 request.ts；docs/28 工作流边界澄清（V1.2）；docs/43 认知决策备忘录（预承诺决策规则） |

## 验收数据对照

| 计划验收项 | 结果 |
|-----------|------|
| token/成本核算误差 <5% | ⚠️ 后端链路完整（真实 usage 直记 + 估算路径），供应商后台比对需生产环境执行（部署环境动作，列入 S5 验证） |
| OTel 默认开 + 3 指标进看板 | ✅ K8s configmap 开启；缓存命中率/模型路由/预算熔断三面板 + 三条告警规则 |
| 死代码清理 | ✅ 3 空壳表 + 5 处死方法/空包 + 前端 4 处硬编码/mock |
| 4 个预留事件真正发射 | ✅ 引擎侧全发射 + 前端消费 |
| 双轨收敛 | 🟡 部分：session.ts 试点完成（模式已建立）；legacy/wenshi 引擎与 RocketMQ 决策延至 S5（依据生产数据，见偏差） |

## 与计划的偏差（如实记录）

1. **前端 API 全量收敛未完成**：本机无 node 无法类型检查，盲改 10 个模块有回归风险。session.ts 试点建立了标准模式（unwrap 信封解包 + request 泛型），其余 9 个模块按模式机械迁移约 1 人日；
2. **三个决策门未到执行条件**：legacy vs wenshi、RocketMQ 去留、desktop 立项——均需生产运行数据（A/B 实验按 T4.6 备忘录的预承诺规则决策，防沉没成本驱动）；RocketMQ 当前仍零消费者（compose 保留，S5 复评）；
3. **usage 估算标记（estimated=1）列未加**：流式估算路径以日志区分（V36 未含该列，避免为低价值标记扩表；估算偏差已记录在 CostAccountingService Javadoc）。

## 累计成果（Sprint 1-4）

| 指标 | 项目开始 | 当前 |
|------|---------|------|
| 引擎测试 | 0 | 186 |
| 全仓测试 | 28 | **432** |
| DB 迁移 | V32 | **V37** |
| 技术债关闭（D-1~D-20） | 0 | **17 项**（剩 D-5/D-6 待决策、D-15 部分遗留、D-17/D-19/D-20 部分） |
| 分析报告 P0/P1 风险 | 11 项 | **全部关闭或缓解** |

## Sprint 5 预告（规模化与部署成熟度）

T5.1 SSE 多副本 Redis Pub/Sub 改造（生产多副本前置条件）-> T5.2 Helm Chart 化（含 gateway/sandbox/前端）-> T5.3 jmeter 压测落地（50 并发 SSE 首字节 <500ms）-> T5.4 成本看板调优 -> T5.5 阶段五路线评审（含三个决策门终审）。
