# S8 续：部署实测缺陷修复轮完成报告

> 日期：2026-09-14
> 范围：部署实测暴露的两个运行时缺陷（glm-5.3-flash 截断 / actuator 探针 401）+ Flyway 接管实测验证

## 一、缺陷 1：glm-5.3-flash「推理过程消耗了全部 token 上限」（P0，已修复并实测验证）

### 现象
用户部署实测报错：`AI 回复被截断：推理过程消耗了全部 token 上限，未生成正式回复。请增大 max_tokens 或简化问题后重试。`

### 根因
- glm-5.3-flash 为推理模型，思考阶段以 `reasoning_content` 流式输出，不占用正文；
- 引擎默认 `maxTokens=8192`（`AgentEngineProperties.Budget`→`defaultMaxTokens`），复杂问题思考阶段即耗尽预算；
- 供应商返回 `finish_reason=length` 且正文为空时，同步/流式两路径均直接抛错终止，无自愈能力。

### 修复（ReactAgentExecutor.java，双路径一致）
1. **截断自愈**：`finish=length` 且正文为空时，自动加倍 max_tokens 重试（上限 `MAX_TOKENS_HARD_CAP=65536`，最多 `MAX_TRUNCATION_RETRIES=2` 次）；
2. **流式上下文清理**：重试前移除本轮的空 assistant 消息，避免污染后续上下文；
3. **用户可见反馈**：流式路径发 STATUS 事件「思考超限，正在扩大预算重试...」；
4. **预算同步升级**（实测发现的次生缺陷）：重试是主动升级动作，但复杂度路由判 L1 时时间预算仅 30s——重试轮次回到 `shouldStop` 即被熔断（`budget_exceeded: token=0/16384`）。修复：升级 max_tokens 时同步 `tokenBudget×2` 且 `timeBudgetMs = max(×2, elapsedMs×3)`。

### 实测验证（走网关 8080，与用户前端路径一致）
| 项 | 结果 |
|---|---|
| 重推理题（a(n+1)=a(n)+1/a(n)² 求 ⌊a₂₀₁₄⌋） | 触发 1 次超限重试（8192→16384）→ 0 错误/熔断 → 1839 字符严格论证（答案 18，正确）→ done + experience_saved |
| 简单题（递归一句话解释） | 无重试，54 正文块 + done 正常收尾 |
| 后端日志 | 两次实测均记录 `自动扩大 max_tokens 至 16384 重试（第 1/2 次）` |
| 测试 | 引擎 186 测试全绿 |

## 二、缺陷 2：管理口 9081 actuator 返回 401（P0，已修复并实测验证）

### 现象
`health-check.sh` 探 `http://localhost:9081/actuator/health` 得 401 `未认证`，自愈脚本会误判 interface 不健康 → 重启循环。

### 根因
`SecurityConfig` 对 `/actuator/**` 一刀切 `denyAll`；管理上下文复用父上下文的 `springSecurityFilterChain`，规则同样作用于 9081 管理口。这正是 S8 计划遗留项「K8s 探针端口修正」的根因——探针无论打 8080 还是 9081 都会被拒。

### 修复（SecurityConfig.java）
- 新增 `@Value("${management.server.port:9081}") managementPort`；
- 在 denyAll 之前加端口匹配规则：`requestMatchers(request -> request.getLocalPort() == managementPort).permitAll()`（管理口仅承载 actuator 端点，放行安全；业务口 8081 维持 denyAll 纵深防御）。

### 实测验证
- `9081/actuator/health` → 完整健康详情（database/redis/diskSpace 全 UP）；
- `8081/actuator/health` → 仍 401（纵深防御未放松）；
- `health-check.sh local` → 三项检查全部通过。

## 三、Flyway 接管实测验证（S8 计划 A1 验收点）

本轮两次重启 interface（加载新 jar），`flyway_schema_history` 确认：

```
38  stability schema alignment  success=1
37  << Flyway Baseline >>       success=1
```

V1 重放炸弹已拆除，V38 幂等对齐迁移正常执行。

## 四、附带观察（不阻塞，留档）

1. **流式路径预算 token 消耗未记账**：`budgetController.consume` 仅在同步路径调用（execute L158），流式路径 tokenUtil 恒为 0，预算仅靠时间/轮次兜底——建议后续补流式 usage 记账；
2. **L1 误判**：重推理题被复杂度路由判为 L1（时间预算 30s）。对推理模型供应商，L1 时间预算偏紧，可考虑按模型是否为推理模型动态调整时间预算；
3. **磁盘水位**：健康检查显示根分区剩余 12.9GB/199GB（约 6.5%），接近需要关注的水位；
4. **LlmJudge deepseek 报错**：judge 供应商已切 `ark-code/glm-5.3-flash`（yml 已改、重启后生效），下一轮跑批时观察确认。

## 五、交付物

| 文件 | 变更 |
|---|---|
| `gewu-agent-engine/.../core/ReactAgentExecutor.java` | 同步+流式双路径截断自愈 + 预算同步升级 |
| `gewu-interface/.../security/SecurityConfig.java` | 管理口 actuator 放行（K8s 探针可用的根因修复） |
| 本报告 | 实测证据链留档 |

## 六、遗留事项（低优先级，用户已确认排期）

- B 组前端收敛 4 模块；done 事件带 messageId；K8s 部署清单探针配置同步；wenshi 知识库填充复评；
- 上述「附带观察」4 项。
