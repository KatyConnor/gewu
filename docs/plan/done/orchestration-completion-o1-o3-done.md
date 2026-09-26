# 编排引擎能力补全 O1~O3 完成报告

> 文档编号：DONE-ORCH-O1-O3
> 日期：2026-09-26
> 依据：docs/design/48-workflow-capability-redesign.md（需求基线）+ docs/plan/exe_plan/orchestration-completion-plan-2026-09.md（实施方案 EXEPLAN-ORCH-2026-09）
> 状态：O1/O2/O3 已完成并本地验收（UAT 冒烟通过）；O4 未启动（可选）；本文档即 O5 的验收归档产物

---

## 一、交付总览

| 阶段 | 需求 | 交付内容 | Commit |
|---|---|---|---|
| O1 可靠性与版本化基座 | WFO-01/02/03/05/06/08(前半) | 图版本化（V49/V50）、下架/回滚、检查点持久化（V51）、节点级重试/超时、变量语法对齐、executionMode 移除 | `2af61b9` |
| O2 结构与人工介入 | WFO-04/07/09 | SUBGRAPH 嵌套子图（SPI+沙箱+VL-13+特性开关）、断点续跑前端闭环、HUMAN 审批人圈定（V52） | `2af61b9` |
| O3 触发体系一期 | WFC-01/02/04 | Agent 工具化 `run_orchestration_graph`（V53）、定时触发（CAS 抢占）、triggerType 可观测 | `9335e93` |
| O3 触发体系收尾 | WFC-03 | Webhook 触发（V54：token SM3 哈希/匿名端点/404 语义/总开关）、设计器触发配置面板 | `18618b8` |
| 配套文档 | — | 48 号设计文档、EXEPLAN-ORCH-2026-09、总计划（BPM 冻结注记）、操作手册 V1.1 | `298e257` + 本轮 |

## 二、UAT 冒烟验收记录（2026-09-25 ~ 09-26，本地环境 gewu_dev）

迁移：V49~V54 六个迁移全部成功应用（Flyway 日志 "now at version v54"）。

| # | 验收项 | 结果 | 证据摘要 |
|---|---|---|---|
| 1 | 版本生命周期 | ✅ | 创建→更新→激活 v1→下架→改定义→再激活 v2→回滚写回 v1；active 状态回滚被业务闸正确拒绝 |
| 2 | 执行绑定版本 | ✅ | 执行记录携带 versionId 与 triggerType=MANUAL |
| 3 | LLM 真实执行 | ✅ | zhipu/glm-4.7-flash 流式执行 SUCCEEDED，节点级记录 1 条（回放数据源） |
| 4 | HITL 审批人圈定 | ✅ | approval_request.assignee_id 落库正确；待审批携带 assigneeId；批准后执行恢复并完成 |
| 5 | SUBGRAPH | ✅ | 父图引用子图同步执行成功（子图产出 236 字符）；变量合并修复后通过 |
| 6 | VL-13 双闸 | ✅ | 引用未激活子图的保存被阻断（返回 VL-13 错误） |
| 7 | Agent 工具注册 | ✅ | 工具目录含 run_orchestration_graph |
| 8 | 定时触发配置 | ✅ | Cron 保存即预计算下次触发时间 |
| 9 | Webhook | ✅ | token 生成（32 字符一次性明文）；匿名触发 202 且执行记录 triggerType=WEBHOOK、SUCCEEDED；错误 token 404 |
| 10 | 检查点持久化 | ✅（逻辑） | 双写/重启回退/恢复即删经单测覆盖（ExecutionControlCheckpointStoreTest×6）；重启实测未单列 |

冒烟期间发现并修复的真缺陷（全部已入库）：

1. 子图定义 variables 未并入子上下文（子图级模型兜底丢失）——修复为"子图变量为基础、父快照覆盖"；
2. 同步执行状态字面量 SUCCESS 与流式 SUCCEEDED 不一致（前端徽标失真）——统一映射；
3. runSync 丢失 graph_complete.reason（error_message 落库为空）——透传失败原因；
4. RunOrchestrationGraphTool 构造期循环依赖（集成测试暴露）——ObjectProvider 延迟解析破环；
5. upsert 依赖 createdAt 启发式判断新建/更新不可靠——显式 isNew 标记；
6. ReactAgentExecutorStreamTest 偶发失败根因（metric 回调异步于断言的并发窗口）——断言前加收敛等待。

## 三、测试与质量

- 全模块 `mvn test`（agent-engine / application / interface 含 E2E 与性能集成）**BUILD SUCCESS**；
- 新增测试用例 26 个：VariableTemplatesTest×5、ExecutionControlCheckpointStoreTest×6、OrchestrationGraphVersionTest×6、OrchestrationWebhookTest×6、RunOrchestrationGraphToolTest×5、OrchestrationScheduleRunnerTest×3、Validator VL-13×5/VL-14×2/VL-09×1（部分类间有交叠按类计数）；
- 前端 `tsc --noEmit` 零错误；界面全部沿用既有设计 token，无硬编码色值。

## 四、部署提示

- **两个特性开关默认关闭**：`agent.engine.orchestration.subgraph.enabled`（SUBGRAPH，联调后开启）、`agent.engine.webhook.enabled`（Webhook，**开启前须安全评审**，见 48 号 R5）；
- Flyway 迁移 V49~V54 为增量 DDL，启动自动执行；存量 active 图在下次激活时产生版本快照，执行加载自动回退兼容；
- Agent 工具时长帽 `agent.engine.tool.orchestration.timeout-seconds` 默认 300 秒。

## 五、遗留与技术债

| # | 项 | 处置建议 |
|---|---|---|
| 1 | O4（可选）：executionMode 的 PLAN_EXECUTE 运行时接线 | 视产品需求排期（2 人日，接线后恢复属性面板下拉） |
| 2 | 同步执行路径不落节点级执行记录（回放以流式为准） | 既有缺口，建议后续统一（runSync 需暴露事件流） |
| 3 | assigneeRole 角色成员过滤基于前端登录态角色列表 | 后端暂无角色展开接口，属精简实现 |
| 4 | Webhook 无请求级限流（依赖 token 随机性 + 总开关） | 公网暴露前补 Redis 计数限流 |
| 5 | 定时触发多实例依赖 CAS 抢占 | K8s 多副本部署前评估是否升级调度器（48 号开放问题 4） |
| 6 | 手册 §10.7 触发体系为 V1.1 增补 | 后续触发能力演进时同步更新 |

## 六、复核建议

- V49~V54 迁移脚本与 `SecurityConfig` Webhook 白名单行：人工复核（安全相关）；
- `ExecutionControl` 检查点双写与 `PipelineModeHandler` 子图失败传播语义：人工复核；
- 全部提交均未 push，推送时机由团队决定。

---

*本报告对应 AETC 留痕会话见 audit/traces/s-20260925-212106 ~ s-20260926-115322 系列。*
