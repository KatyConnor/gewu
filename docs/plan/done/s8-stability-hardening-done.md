# S8「稳定性与部署硬化」完成报告

> **日期**：2026-09-14 | **执行依据**：用户确认的 S8 优先级方案（P0 稳定性专项先行）+ 三路定向勘察新发现
> **验收结论**：✅ 全部交付，439 测试绿，Flyway 首次在主库真实接管（baseline 37 + V38），Tomcat 升至 10.1.36

## 交付清单（2 个提交）

| 提交 | 内容 |
|------|------|
| `c7a3147` | S8 主体：Flyway 接管 + 网关熔断修复 + SSE 泄漏修复 + Tomcat 10.1.36 + access log + 自愈巡检 |
| `812a715` | 部署手册 FAQ 补充（熔断 17002 / interface 长运行挂起） |

## 勘察驱动的关键新发现（本轮价值核心）

三路定向勘察在"已知问题"之外挖出**四个此前未知的重大隐患**，其中两个可直接解释本轮部署实测的全部异常：

1. **网关默认 1s TimeLimiter**（从未配置 TimeLimiter）——SSE 流式与分钟级 LLM 推理请求被 1 秒掐断转 17002，且计入失败率加速熔断开门。这解释了部署测试中 17002"服务熔断中"的快速出现（已禁用，SSE/LLM 长请求放行）。
2. **K8s 探针恒失败**：探针打业务口 8080 的 `/actuator/**`，被 SecurityConfig `denyAll` 拦截——K8s 部署下 Pod 陷入重启循环。**管理口 9081 才是 actuator**（免认证、独立于业务线程池）——K8s deployment 探针需改指 9081（列入 S9）。
3. **prod yml 两处配置失效**：`max-threads/min-spare-threads` 是 Boot 2.3 起废弃的旧键（写了个寂寞）；rollingpolicy 是死配置（无文件输出则滚动不生效）。
4. **RateLimitFilter 在 Netty event loop 上同步调 Redis** 且异常无捕获——Redis 故障时网关全局 500 而非降级。

## S8 交付明细

### A. 拆弹与配置加固
- **Flyway baseline 37**：拆除"下次重启 V1 全量重放炸库"的定时炸弹；V33~V37 与既有库逐项实测无冲突
- **V38 幂等对齐迁移**：10 表补 BaseEntity 列（含 sandbox_audit_log.created_at 这个 ORM 必需缺口），守卫式存储过程模式（列存在则跳过），临时库演练 + 真实库接管双重验证
- **网关熔断治理**：disable-time-limiter + minimum-number-of-calls 20 + slow-call 阈值 30s/80% + 自动半开

### B. interface 修复与取证设施
- **SSE 写失败泄漏修复**：写失败即 countDown + dispose（原持续失败占线程直至 10 分钟超时）
- **SseEmitter 30 分钟超时**（原 0L 永不超时）
- **Tomcat 10.1.20 → 10.1.36 覆写**（`<tomcat.version>` 单属性，16 个月 NIO/Poller/async 修复，直击挂起形态）
- **access log 启用**（%D %F 取证）+ prod yml 失效旧键清理 + gewu-ctl.sh JVM 参数对齐（GC log/HeapDump）

### C. 自愈与可观测
- health-check.sh V2：9081 管理口探活（免认证、独立线程池）+ 业务口探活 + 连续 3 次失败自动重启自愈 + 状态落盘；修 `((ERRORS++))` set -e 陷阱
- 网关熔断治理四项配置 + RateLimitFilter Redis 异常降级放行 + JwtAuthFilter claims 缺失防御

## 部署实测缺陷的最终闭环状态

本轮部署测试暴露的全部问题已闭环：

| 问题 | 根因层级 | 状态 |
|------|---------|------|
| 多轮上下文断裂（用户原始投诉） | 三层：wenshi 历史缺失 + 经验毒丸 + 供应商配置损坏 | ✅ 全部根治 |
| 跑批 Agent 创建 500 | XSS 过滤器破坏内嵌 JSON | ✅（`809fb82`） |
| 跑批对话 500 | agent_tool 缺 created_by | ✅（ALTER + V38） |
| 执行记录从未落库 | input/output JSON 列错配 | ✅（TEXT + V38） |
| 评分全 None | judge 供应商停用 + totalScore 字段 | ✅（judge 切 glm + 脚本修复） |
| 17002 服务熔断中 | 1s TimeLimiter + 熔断器开门 | ✅（TimeLimiter 禁用 + 配置治理） |
| interface 长运行挂起 | Tomcat 10.1.20 NIO（待复发观察确认） | 🟡 升级 10.1.36 + 自愈巡检兜底 |

## 系统性改进（防复发）

1. **主库 Flyway 正式接管**（`FlywayMigrationConfig` 显式 Bean，修复 @ConditionalOnMissingBean 退避机制）——此后 V39+ 迁移自动生效，schema 漂移不再依赖手工；
2. **健康检查自愈闭环**：9081 管理口探活（独立于业务线程池，挂起时仍可判定）→ 连续失败自动重启 → 状态落盘；
3. **取证设施**：access log（%D 耗时/%F 首字节）、GC log、HeapDumpOnOutOfMemoryError、prod rolling policy 修复（原为死配置）。

## 遗留与后续

| 事项 | 状态 |
|------|------|
| interface 挂起根因确认 | 🟡 Tomcat 10.1.36 覆写为最大概率修复；复发时按 FAQ 取证流程抓证据，若复发则升级 Boot 3.4.3（本地物料已备，需 gateway spring-cloud 联动） |
| B 组前端收敛 4 模块 + messageId 小改造 | 待排期（约 1.5 人日） |
| wenshi 知识库填充后复评 | 触发器就绪（同题集 + 分析器） |
| K8s 探针端口修正（8080 → 9081） | 列入下次 K8s 变更（1 行） |

**监控基线**：当前服务运行于 Tomcat 10.1.36 + 修复后代码；`agents` 端点探活 + 自愈巡检已部署，挂起复发将被自动检测与恢复。
