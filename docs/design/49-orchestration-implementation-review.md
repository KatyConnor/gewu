# 编排引擎设计实现独立评审报告

> 编号：49-orchestration-implementation-review
> 版本：V1.0 · 2026-09-26
> 性质：设计实现评审报告（独立子代理执行，评审基线 = O1~O3 全部改动 + 既有编排核心，commit `2af61b9`~`43b5e91` 已推送状态）
> 关联：48（需求基线）、EXEPLAN-ORCH-2026-09（实施方案）、done/orchestration-completion-o1-o3-done.md（验收归档）

---

## 一、评审方式与范围

独立评审代理对编排核心链路（引擎 ExecutionControl/Orchestrator/PipelineModeHandler、应用层 OrchestrationService/Validator/CheckpointStore/SubgraphResolver/Agent 工具/定时 Runner、Controller 与 SecurityConfig、V49~V54 迁移、前端触发与版本面板）按 **A 安全 / B 可靠性并发 / C 正确性一致性 / D 工程质量** 四维度逐项审查，全部发现附 file:line 证据。

## 二、总评

O1~O3 的能力骨架（版本化、检查点双写、SUBGRAPH 双闸、Webhook 哈希凭证、CAS 调度）方向正确、分层干净；安全基线（SM3 哈希、统一 404、总开关默认关）达标；V49~V54 与实体逐列对齐、OceanBase MySQL 模式兼容。评审发现 **一条贯穿性状态桥接缺陷**（流式路径把 PAUSED/CANCELLED 覆写为 SUCCEEDED，使续跑闭环在主流式路径失效）与**定时调度 NULL 锁停摆**两项 P1，以及 Webhook 滥用面等 9 项 P2；均已分级处置（8 项本轮修复、5 项列入遗留跟踪）。

## 三、发现与处置清单

### P1（重要应修）

| # | 发现 | 证据 | 处置 |
|---|---|---|---|
| F-01 | **流式路径把 PAUSED/CANCELLED 终态覆写为 SUCCEEDED**：doOnComplete 只判 FAILED，暂停/取消后恢复入口消失、检查点成孤儿 | OrchestrationService doOnComplete vs PipelineModeHandler pauseHere/cancelHere | ✅ **本轮修复**：终态全量映射（mapEngineStatus），postProcess 仅业务终态触发 |
| F-02 | **定时调度 NULL 锁停摆**：CAS 抢占置 next_fire_at=NULL，执行中途进程崩溃则 finally 不执行，NULL 永久残留且扫描条件跳过 NULL 行，无告警 | OrchestrationScheduleRunner fire/scanAndFire | ✅ **本轮修复**：抢占即推进 next_fire_at 到下次时间（不再置 NULL），新增 repairStuckSchedules 自愈（每分钟修复 NULL 行并告警） |
| F-03 | **PLAN/SUBGRAPH 子 Walk 共享 executionId 与 sink**：子 Walk 内暂停保存的检查点为子图且会被父 Walk 收尾时的 unregister 删除；子图失败提前 unregister+complete 导致父图空转 | PipelineModeHandler 子 Walk 构造/pauseHere/failGraph | ⏳ **列入遗留**（受控：SUBGRAPH 特性开关默认关、PLAN 现网为完成即收尾路径）。修复方向：子 Walk 终态一律委托宿主 completionCallback，禁止子 Walk 直接 unregister/completeGraph |
| F-04 | **Webhook 匿名端点无频控/无 body 上限/同步阻塞**：可被反复触发整图 LLM 消耗，body 原样入库 | OrchestrationController triggerByWebhook | ✅ **部分修复**：body 上限 64KB（超限 404）；⚠️ 限流与异步执行（先落 PENDING 即返 202、后台 worker 执行）列入遗留，**生产开启总开关前必须补齐** |

### P2（建议）

| # | 发现 | 处置 |
|---|---|---|
| F-05 | Agent 工具超时后底层执行未取消且提示语与事实不符；池饱和（CallerRunsPolicy）时时长帽失效 | ✅ 本轮修复：future.cancel(true) + 提示语改为"仍在后台执行"；池饱和降级语义维持并注释说明 |
| F-06 | resume 无检查点时状态置 RUNNING 后永久卡死 | ✅ 本轮修复：resumable=false 保持 PAUSED 不前移 |
| F-07 | 同步路径 PAUSED/CANCELLED 被 runSync 硬编码转 FAILED，形成"FAILED 却可续跑"矛盾 | ✅ 本轮修复：runSync 按 builder 透传引擎终态 |
| F-08 | 续跑图来源双轨：SUBGRAPH 内检查点存子图 + 运行期子图取数未绑定父执行版本 | ⏳ 列入遗留，随 F-03 一并修复（检查点保存父链状态；子图按父版本激活时刻取数） |
| F-09 | VL-09 前后端正则不一致：`${my-var}` 校验通过但运行期不替换 | ✅ 本轮修复：VariableTemplates 正则统一加连字符 |
| F-10 | ${} 纯文本替换进 TOOL arguments 无 JSON 转义（上游含引号即碎；有 Schema 校验兜底降级） | ⏳ 列入遗留：TOOL 参数模板改结构化渲染 |
| F-11 | 匿名 webhook 202 响应回传完整执行实体（含 graphSnapshot/variables） | ✅ 本轮修复：匿名响应仅回 executionId + status |
| F-12 | 测试缺口：SUBGRAPH 引擎层 0 测试、ScheduleRunner CAS/崩溃窗口 0 覆盖、流式 pause/cancel 状态桥接无 Service 级测试等 | ⏳ 列入遗留：优先补 F-01/F-02/F-03 回归测试 |
| F-13 | 日志小疵：TOOL 失败 ERROR 无原因 | ✅ 本轮修复（VariableTemplates 占位符经复核实际正确，未改动） |

### 通过项确认

Webhook token 安全基线（一次性明文/SM3/统一 404/总开关默认关）✅；白名单范围精确（仅 webhooks/** 子树）✅；SUBGRAPH 无绕过（唯一入口强制 active+版本快照+深度上限+VL-13 双闸）✅；检查点双写自洽（6 单测）✅；版本化取数与回滚一致性 ✅；V49~V54 与实体/OceanBase 兼容 ✅；前端调用正确性（触发面板/版本面板/VL-14 输入约束）✅；审批人圈定全链路 ✅；日志无敏感信息 ✅。

## 四、遗留跟踪表（新增）

| # | 项 | 优先级 | 触发条件 |
|---|---|---|---|
| L1 | F-03/F-08 子 Walk 终态委托与父链检查点 | P1（SUBGRAPH 开启前必须） | 开启 subgraph.enabled 前 |
| L2 | F-04 剩余：Webhook 限流（RateLimit 接入）与异步执行 | P1（Webhook 开启前必须） | 开启 webhook.enabled 前 |
| L3 | F-10 TOOL 参数结构化渲染 | P2 | 出现工具参数碎裂实报时 |
| L4 | F-12 补回归测试（F-01/F-02/F-03 路径） | P2 | 下一测试轮 |
| L5 | O4 PLAN_EXECUTE 接线 | P2-11（roadmap 已记） | 见 followup-iteration-plan.md |

## 五、结论

编排引擎 O1~O3 实现整体达到可交付水平，**两项 P1 状态桥接缺陷已修复**；SUBGRAPH 与 Webhook 两个特性开关维持默认关闭，开启前分别须完成 L1、L2 的遗留修复——该前提已同步写入操作手册附录 D 与 done 文档。

---

*评审执行：独立评审代理（2026-09-26）；修复验证：全模块 mvn test BUILD SUCCESS（application 201 用例全绿）+ 前端 tsc 零错误。*
