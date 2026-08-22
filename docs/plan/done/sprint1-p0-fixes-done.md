# Sprint 1（P0 止血）完成报告

> **日期**：2026-08-22 | **执行依据**：`docs/plan/exe_plan/optimization-implementation-plan-2026-08.md` Sprint 1
> **计划工期**：2 周 | **实际**：1 个工作会话（AI 辅助编码节奏）
> **验收结论**：✅ 全部 6 项任务完成，9 模块 BUILD SUCCESS，全量测试绿

## 任务完成情况

| 任务 | 状态 | 交付物 | 提交 |
|------|------|--------|------|
| T1.1 悬空变更入库 | ✅ | gewu-web Vite->Next 迁移 162 文件 + 桌面端设计/实施计划文档分主题入库 | `45ff332` `e681604` |
| T1.2 LLM 协议修复 | ✅ | Message.toolCalls + 构建器序列化 + 双路径回灌 + 流式 maxTokens 透传 + 增量关联修复；10 单测 | `0717c67` |
| T1.3 三个确定性 bug | ✅ | ToolConfigSource 语义 / 注入异常统一 / Debate metadata 合并；8 单测 | `bf00738` |
| T1.4 seq 原子化 + 幂等 | ✅ | V33 迁移 + SessionMessageAppender（冲突重试+原子计数）+ clientId 全链路（DTO/Controller/前端）；6 单测 | `c8c6c41` |
| T1.5 参数配置化 | ✅ | budget/lifecycle/history-limit/request-timeout 全部提为 agent.engine.* 配置 + D-11 reasoning 回退修复；11 单测 | `1325446` |
| T1.6 异常路径消息保全 | ✅ | 流式中断但已有内容时落库并追加中断标记 | `a500d88` |

## 测试与验收数据

- **gewu-agent-engine**：0 -> **29 个测试**（构建器 7 / 执行器流式 3 / 工具配置源 2 / 注入检测 5 / Debate 1 / 预算 7 / LLM 客户端 4）
- **gewu-application**：109 个测试全绿（新增 AppenderTest 6 + 适配 CompressionTest 新构造）
- **gewu-interface**：24 个测试全绿，含 **E2EIntegrationTest / PerformanceBenchmarkTest 通过**（验证 Spring 全上下文装配与 AutoConfiguration 新接线）
- 全模块 `mvn -T 1C test`：**BUILD SUCCESS**

## 计划外发现并修复的缺陷（3 个）

1. **流式工具调用增量关联缺陷**（T1.2 测试暴露）：标准 OpenAI 协议 id 仅在首个分块出现，原 `accumulateToolCall` 把无 id 分块路由到独立 "default" 累加器导致参数丢失；已按顺序关联修复；
2. **流式 maxTokens 丢失**：`streamRound` 写死 `resolveMaxTokens(null)`，任务级 maxTokens 被忽略；已参数化透传；
3. **InOrder+times 断言语义**：测试基建踩坑记录（Mockito 二次 verify 只统计校验点后调用），已沉淀正确写法。

## 环境备注

- 本机无 JDK/Maven，已部署 `~/tools/jdk-21.0.12+8` + `~/tools/apache-maven-3.9.16`，`~/.m2/settings.xml` 配置阿里云镜像；
- 数据库迁移推进至 **V33**（消息幂等唯一键），需在部署环境执行 Flyway。

## Sprint 2 预告（引擎测试补齐）

按计划执行 T2.1 测试基建（FixedLlmClient 已随 T1.2 沉淀雏形）-> T2.2 补齐 22+ 测试类（当前已完成 6 个）-> T2.3 JaCoCo 门禁进 CI。重点剩余：同步执行器路径、SecurityChain 5 组件、编排模式（Pipeline/Swarm）、AntiRunawayGuard、MCP 客户端。
