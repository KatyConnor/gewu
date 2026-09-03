# 决策门终审（Sprint 5 · T5.5）

> **日期**：2026-09-01 | **依据**：优化实施计划三个决策门（双引擎/RocketMQ/desktop）+ 认知层决策备忘录（43 号文档）
> **结论摘要**：两门延后待数据（带触发条件与截止时间），一门立即执行（RocketMQ 移除排期）

## 门 1：LLM 引擎 legacy vs wenshi

**现状**：`gewu.wenshi.routing.stream=wenshi`（生产流式已走 Wenshi），`chat=legacy`（同步仍走收敛引擎）。
A/B 分组记录（T3.4）已就绪但**生产样本量未达备忘录阈值**（每组 ≥50 样本、≥3 轮）。

**终审结论：延后，触发条件驱动**
- 触发条件：experiment-compare 报表 `baseline` 与 `full_stack` 组各 ≥50 样本
- 决策规则：按 docs/design/43 预承诺规则执行（Δ质量 ≥+5% 且 Δ成本 ≤+20% 接线全开，否则收敛到胜者）
- **截止时间：2026-Q4 末**；逾期未达样本量则按"流式胜出"收敛：同步链路切 WenshiReasoningEngine，LegacyLlmClientAdapter 归档
- 当前配置即最小风险态（两引擎各服务一条链路，无互相干扰）

## 门 2：RocketMQ 去留

**现状**：全仓 0 发布方调用、0 消费者（Sprint 1 分析确认，四个 Sprint 期间无新增调用）；compose/K8s/pom 均保留脚手架。

**终审结论：立即执行移除排期（下一迭代）**
- 依据：四个 Sprint 的开发均未产生真实消费者需求；保留未用中间件徒增部署复杂度与资源占用（broker 2C2G）
- 移除清单（S6 执行）：pom 依赖 rocketmq-spring、compose 两套的 namesrv/broker/dashboard、K8s configmap 的 ROCKETMQ_NAMESERVER、application.yml 的 rocketmq 配置组
- 保留项：`DomainEventPublisher` 接口与其 Spring ApplicationEvent 本地实现（事件语义有价值，本地事件已可满足当前通知/统计解耦需求；跨实例事件待真需求出现再引入 MQ）
- 执行人：下一迭代负责人；工作量约 0.5 人日

## 门 3：gewu-desktop 立项 or 下线

**现状**：空目录 + docs/design/42 桌面端架构设计 V1.0（评审中）。

**终审结论：保留目录，设计评审通过后立项（不阻塞主线）**
- 42 号设计已给出完整架构（Electron 主进程 + 渲染层复用 gewu-web + 本地 Agent 运行时），说明该方向存在明确设计意图而非废弃残留
- 立项条件：平台主链路（Sprint 1-4 交付）在 Web 端稳定运行 ≥1 个月且无 P0/P1 缺陷积压
- 在立项前保持空目录不删除（git 历史中目录删除/恢复成本不对称）

## 门 4（补充）：认知层 D-5/D-6

按 docs/design/43 备忘录执行，与门 1 同批数据决策（同截止时间），此处不重复。

## 决策跟踪表

| 门 | 决策 | 状态 | 责任 | 复查时点 |
|----|------|------|------|---------|
| 1 引擎收敛 | 延后（数据驱动） | 🟡 待样本 | 平台负责人 | 2026-Q4 末 |
| 2 RocketMQ | 移除完成（S6：pom/配置/compose/K8s 全清理，DomainEventPublisher 转本地事件实现） | ✅ 已执行 | 已完成 | - |
| 3 desktop | 保留+条件立项 | 🟢 观察中 | 产品负责人 | 主链路稳定 1 个月后 |
| 4 认知 D-5/D-6 | 延后（数据驱动） | 🟡 待样本 | 平台负责人 | 2026-Q4 末（与门 1 同批） |
