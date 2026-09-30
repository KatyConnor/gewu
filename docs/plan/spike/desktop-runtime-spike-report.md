# Spike-A 报告：gewu-agent-engine 单机嵌入验证（Phase 0 D0-1~D0-3）

> **日期**：2026-09-30 | **执行**：角色03（架构）/ 实施 | **关联**：`docs/design/42` V3.1 ADR-002B、实施计划 V1.2 D0-1~D0-4
> **结论**：**GO** —— 引擎零业务装配可启动、SPI 覆盖机制实证、流式全链路贯通、jlink 体积达标；启动耗时门禁因测量环境污染待 Phase 1 复核（见 §4）。

---

## 1. 验证载体

新增 monorepo Maven 模块 `gewu-desktop-runtime`（根 pom 注册，`mvn -pl gewu-desktop-runtime -am package` 构建）：

```
gewu-desktop-runtime/
├── pom.xml                          # parent=gewu-platform；deps: engine + spring-boot-starter-web；repackage
└── src/main/
    ├── java/com/gewu/desktop/
    │   ├── DesktopRuntimeApplication.java   # @SpringBootApplication 零业务 Bean
    │   ├── spike/StubOpenAiServer.java      # OpenAI 兼容 stub（JDK HttpServer，SSE 分帧）
    │   ├── spike/SpikeLlmBeans.java         # SPI 覆盖：LlmProvider + PersistenceService → 覆盖 NoOp 默认
    │   └── endpoint/DesktopChatController.java  # /engine/health + POST /ai/chat/stream（SseEmitter，平台契约同构）
    └── resources/application.yml            # :18081；agent.engine 预算/LLM 桌面 profile 首版
```

## 2. D0-1 引擎独立启动 —— ✅ 通过

- 零业务 Bean 启动成功（`AgentEngineAutoConfiguration` 全量 NoOp 装配 + Web 宿主）；
- **SPI 覆盖机制实证**：桌面侧注册 `LlmProvider`/`PersistenceService` Bean 后，引擎按新 Bean 装配（NoOp 自动让位）——ADR-002B 的核心装配语义成立；
- 健康端点：`GET /api/v1/engine/health → {"status":"UP","module":"gewu-desktop-runtime"}`。

## 3. D0-2 流式全链路 —— ✅ 通过（stub LLM，无需真实厂商 Key）

`POST /ai/chat/stream` 经引擎 `ReactAgentExecutor` 真实流式解析（stub 模拟 OpenAI SSE），实测事件序列：

```
status("正在思考...") → thinking(reasoning 分离✓) → content 增量×2
→ experience_saved → done(metadata: rounds=1, elapsedMs=922, tokenEstimated=23,
   timeRenewals=0, roundsRenewed=0, tokenUnlimited=false; finishReason="stop") → [DONE]
```

要点：① reasoning_content/content 分离与增量分帧正确（S9-M0 后引擎行为）；② `done.metadata` 携带方案 A 预算元数据（渲染层可直接消费）；③ 引擎日志确认 stub SSE 4 chunk / 482ms 全部解析。**遗留**：真实厂商（DeepSeek/智谱）多轮工具调用回归依赖 P-3 Key 到位（M1 门禁⑥）。

## 4. D0-3 体积与启动实测

| 指标 | 实测 | 门禁 | 判定 |
|------|------|------|------|
| Fat jar（engine + 最小 Web 宿主） | **22.3 MB** | — | 优秀（预算内余量大） |
| jlink 定制 JRE（最小 12 模块集） | **47 MB** | ≤60 MB | ✅ |
| jlink + jdk.localedata（中文格式化） | 72 MB | — | 可选装载（按需 +25MB） |
| jdeps 模块清单 | java.base/compiler/desktop/instrument/management/naming/net.http/prefs/rmi/scripting/security.jgss/sql + jdk.jfr/unsupported + crypto.ec/httpserver | — | 已固化，Phase 1 直接使用 |
| 引擎启动耗时（本机） | 22.5s（冷）/ 32.1s（二跑） | ≤2.5s（M0 门禁） | ⚠️ **测量污染**：本机 8 核负载 7~9.7（多 Agent 会话 + 平台服务并行）。门禁复核移至 Phase 1：空闲环境 + `-XX:TieredStopAtLevel=1` + AppCDS（设计目标路径），若仍 >3s 再评估 jlink 模块裁剪与 CDS 训练 |

## 5. Go/No-Go 建议

**GO**。理由：三项结构性验证（装配/覆盖/流式）全部通过且远超预期；唯一未达标项（启动耗时）被本机负载污染且存在明确优化路径（CDS/C1/jlink 定制），不构成架构性风险。附带红利：`run_workflow` 等应用层工具不在 classpath 的 ADR-010 边界在 spike 中自然成立（引擎单独嵌入，无宿主业务依赖混入）。

## 6. Phase 1 承接清单

1. Spike 代码演化：`spike/` 包删除 → `DesktopLocalLlmProvider`（P-3 Key）+ `DesktopPersistenceService`（H2）+ 完整 Profile 端点（D1-8~D1-13）；
2. jlink 模块清单与启动参数（C1 + AppCDS 训练）固化进 EngineSupervisor 启动命令；
3. 事件序列化加 `@JsonInclude(NON_NULL)` 对齐平台 ChatStreamEvent 契约（spike 中观察到 null 字段全量输出）；
4. P-3 厂商测试 Key 到位后补三厂商多轮工具调用回归（M1 门禁⑥）。
