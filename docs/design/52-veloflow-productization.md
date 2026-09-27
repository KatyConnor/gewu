# Veloflow 独立流程引擎 · 产品化方案

> 编号：52-veloflow-productization
> 版本：V1.0 · 2026-09-27
> 性质：产品化设计 + 实施方案（依据用户决策：工作流引擎抽离为独立流程引擎产品，可单独商用）
> 关联：50（调研）、51（引擎设计，能力基线）、28/30/31（历史设计，分化点已在 50 号勘误）
> 代码基线：分支 `fix/sandbox-security-storage`（P1 内核已交付 30cdd7d/b899222）

---

## 一、产品定义

### 1.1 产品命名

| 项 | 裁定 |
|---|---|
| **产品名** | **Veloflow**（Velo = velocity 迅捷 + Flow 流程；中文可称「维洛流程引擎」） |
| 表前缀 | `VLF_`（参照 Flowable `ACT_`、Camunda `CAM_` 惯例） |
| Maven 坐标 | `com.veloflow:veloflow-engine`（groupId 与 gewu 完全独立，满足单独商用） |
| Java 包根 | `com.veloflow.engine` |
| 配置前缀 | `veloflow.*` |
| 备选名 | Flownex / Nexflow（如需更换，全局替换产品名与前缀即可，结构不变） |

### 1.2 产品定位（一句话）

> **Veloflow 是一个可独立商用的轻量级流程引擎产品**：引入一个 Maven 依赖 + 执行一份初始化脚本，即可为任意 Spring Boot 应用赋予「流程编排 + 人工任务治理 + 多方式触发 + 审计监控」的完整流程能力——能力面对标 Camunda/Flowable（治理深度）与 n8n（节点自动化广度）的交集，以「持久化等待驱动内核」为技术底座。

### 1.3 与 gewu 平台的关系

Veloflow 为独立产品（独立坐标/包名/表前缀/文档），gewu 平台作为**第一个宿主应用**引入它并实现其 SPI（身份桥接）；平台现有 workflow 模块按「双轨过渡 → 切换 → 退役」三步退出（见 §七）。

---

## 二、产品化能力清单（对标 BPM 产品全面能力）

在 51 号 8 层能力模型（C1~C8，P1 内核已交付 C3/C5 骨干）基础上，产品化补齐以下能力：

| # | 能力 | 产品化要求 | 分期 |
|---|---|---|---|
| P-01 | **Starter 自动装配** | `@AutoConfiguration` + 组件/Mapper 扫描，宿主零配置引入即用；REST 可选开关 `veloflow.rest.enabled` | P0 |
| P-02 | **身份 SPI** | `FlowIdentityProvider`（当前用户/用户名/批量姓名）——引擎不依赖宿主登录态；默认实现透传 "system" | P0 |
| P-03 | **初始化脚本** | `db/init/veloflow_init.sql` 全量建表（11 张 VLF_ 表），一键执行即可运行；后续版本演进脚本独立目录 | P0 |
| P-04 | **独立基础设施** | 自带 BaseEntity/VLF ID 生成/异常体系（VeloflowException + 41001 段错误码）/分页对象/Result 包装——**零 com.gewu 依赖** | P0 |
| P-05 | **表前缀** | 全部表 `VLF_` 前缀；引擎代码零硬编码表名（MyBatis-Plus @TableName 唯一出口） | P0 |
| P-06 | **审计切面** | @WorkflowAudit AOP（PRD F-036），180 天保留策略 | P2 |
| P-07 | **多租户预留** | 全表 `tenant_id` 列预留（默认 "default"），租户隔离策略 P2 设计 | P2 |
| P-08 | **监控端点** | /monitor 三端点 + Micrometer 指标 | P2 |
| P-09 | **事件 SPI** | 流程事件（启动/完成/超时）对宿主广播（Spring Event） | P2 |
| C1~C8 | 引擎能力 | 51 号既定路线（人工任务治理 P2 / 触发 P3 / AI 桥接 P4 / 设计器 P5） | P1~P5 |

---

## 三、模块架构

### 3.1 Maven 结构

```
veloflow-engine/                     ← 新独立模块（本仓库内开发，可整体对外发布）
  pom.xml                            ← com.veloflow:veloflow-engine，仅依赖
                                       spring-boot-starter(web 可选)/mybatis-plus/jackson
  src/main/java/com/veloflow/engine/
    VeloflowAutoConfiguration.java   ← @AutoConfiguration + @MapperScan + 条件装配
    commons/                         ← BaseEntity、VlfId、VeloflowException、
                                        FlowPage(Result)、FlowResult、错误码
    identity/                        ← FlowIdentityProvider SPI + 默认实现
    persistence/model/               ← 11 实体（VLF_ 表名）
    persistence/mapper/              ← 11 Mapper
    definition/                      ← WorkflowService（定义/校验/发布/版本）
    runtime/                         ← WorkflowScheduler、WorkflowNodeHandler 注册表、
                                        WorkflowExpressionEvaluator、WorkflowTimerRunner、
                                        WorkflowDefinitionValidator（WV）
    runtime/handler/                 ← 8 类业务节点 Handler
    web/                             ← WorkflowController / WorkflowInstanceController
                                        （veloflow.rest.enabled=true 时装配）
  src/main/resources/
    db/init/veloflow_init.sql        ← 全量建表（11 张 VLF_ 表）
    db/upgrade/                      ← 版本演进脚本（V2 起追加）
    META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### 3.2 解耦点（从 gewu 平台剥离的依赖）

| 原依赖（com.gewu） | Veloflow 替代 | 说明 |
|---|---|---|
| common.entity.BaseEntity | engine.commons.BaseEntity | 同构审计列（deleted/created_at/updated_at/created_by/updated_by） |
| common.ulid.Ulid | engine.commons.VlfId | 自带 ULID 生成（26 字符有序 ID） |
| common.context.UserContext | identity.FlowIdentityProvider SPI | 宿主桥接平台登录态；默认透传 system |
| common.result.* | engine.commons.VeloflowException / FlowResult / 错误码 41001 段 | 独立异常与响应包装 |
| common.dto.PageQuery/PageResult | engine.commons.FlowPage / FlowPageResult | 独立分页 |
| infrastructure.mapper.* | engine.persistence.mapper.* | 同构迁移 |
| domain.workflow.* | engine.persistence.model.* | 同构迁移 |
| UserAccountMapper（批量姓名） | FlowIdentityProvider.batchUserNames | SPI 化 |

---

## 四、表结构（VLF_ 前缀，11 张）

| 原表 | VLF_ 表 | 变更 |
|---|---|---|
| workflow | VLF_WORKFLOW | + tenant_id 预留 |
| workflow_node | VLF_WORKFLOW_NODE | 含 biz_node_id（V57）、+ tenant_id |
| workflow_transition | VLF_WORKFLOW_TRANSITION | + tenant_id |
| workflow_instance | VLF_WORKFLOW_INSTANCE | 含 trigger_type/final_output/version_id（V55）、+ tenant_id |
| workflow_node_instance | VLF_WORKFLOW_NODE_INSTANCE | 含 branch_key/iteration/retry_count/timeout_at（V55）；input/output **LONGTEXT**（V56） |
| workflow_notification | VLF_WORKFLOW_NOTIFICATION | + tenant_id |
| workflow_permission | VLF_WORKFLOW_PERMISSION | 本期开始消费 |
| workflow_audit_log | VLF_WORKFLOW_AUDIT_LOG | 审计全字段（F-036） |
| （新增） | VLF_WORKFLOW_VERSION | 版本快照（51 号 WFO-01 模式） |
| （新增） | VLF_WORKFLOW_SCHEDULE | 定时触发（CAS 抢占即推进 + 自愈） |
| （新增） | VLF_WORKFLOW_WEBHOOK | Webhook（SM3 哈希/404 语义/总开关） |

> workflow_permission_matrix（研发阶段矩阵）**不迁移**（语义废弃，50 号已裁定）。

---

## 五、集成方式（宿主三步接入）

```xml
<dependency>
  <groupId>com.veloflow</groupId>
  <artifactId>veloflow-engine</artifactId>
  <version>1.0.0</version>
</dependency>
```

1. 执行 `veloflow_init.sql`（一次建 11 张 VLF_ 表）；
2. 实现 `FlowIdentityProvider`（桥接宿主登录态；不实现则默认 system）；
3. 配置 `veloflow.rest.enabled=true`（可选，启用内置 REST API）。

引擎零硬编码业务依赖；数据源/事务由宿主 Spring 环境提供。

---

## 六、gewu 平台切换与退役计划

| 步骤 | 内容 | 风险控制 |
|---|---|---|
| S1（本轮） | veloflow-engine 模块建成（代码迁移+init 脚本+测试），平台 workflow 旧模块保留 | 双轨过渡 |
| S2 | 平台引入 veloflow-engine 依赖 + IdentityProvider 桥接 UserContext；Controller 切换 | 灰度 |
| S3 | 存量数据迁移脚本（workflow_* → VLF_*，INSERT SELECT + biz_node_id 回填） | 迁移脚本幂等 |
| S4 | 退役旧 workflow 模块代码与表（归档） | 评审后执行 |

---

## 七、分期路线

| 期 | 内容 | 状态 |
|---|---|---|
| P0 产品骨架 | 模块建成 + 代码迁移 + init 脚本 + 自动装配 + 单测（**本轮**） | 实施中 |
| P1 平台切换 | S2~S3 平台接入与数据迁移 | 下轮 |
| P2 人工任务治理 | task/approval 等待型语义、会签 ANY/ALL/RATIO、超时三动作（escalate=通知管理员）、驳回回退原语、委托、my-todos、审计 AOP | — |
| P3 触发体系 | schedule/webhook/event/upstream（模式平移编排轨已验证实现） | — |
| P4 AI 桥接 | llm/agent/orchestration/knowledge 四节点（编排交互经 SPI，引擎不硬依赖编排产品）+ run_workflow Agent 工具 | — |
| P5 设计器与可观测 | React Flow 设计器、节点时间线、监控仪表盘、导入导出与模板库 | — |

---

## 八、实施要点（P0）

1. 迁移以**机械重命名为主**（包/表前缀/坐标），逻辑零改动——P1 已冒烟验证的内核行为不变；
2. 解耦点手工处理（§3.2 六项）；
3. `VlfId` 自带 ULID 生成（Crockford 32 进制 26 字符，与现有 ID 形态一致）；
4. 自动装配：`@AutoConfiguration + @ComponentScan("com.veloflow.engine") + @MapperScan`，REST/定时器条件装配；
5. 测试：表达式与调度器单测随迁 + 新增装配冒烟（上下文可起）；
6. 验收：模块独立编译/测试全绿 + **grep 验证零 com.gewu 引用** + init 脚本建表齐全。
