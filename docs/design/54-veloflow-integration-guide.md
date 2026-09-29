# 54 - Veloflow 宿主接入指南（详细版）

> 52 号产品化方案的"宿主三步接入"落地文档。快速上手见 `veloflow-engine/README.md`，
> 本文覆盖多数据源路由、SPI 桥接范例、版本迁移与常见问题。
> 关联：51 号（设计实现）、52 号（产品化）、53 号（节点体系 v2，34 种）。

## 一、接入模型

```
宿主应用（Spring Boot 3.2+）
 ├── 引入 veloflow-engine 依赖（自动装配：REST + 调度器 + 定时扫描器）
 ├── 主数据库执行 db/init/veloflow_init.sql（14 张 VLF_ 前缀表）
 ├── （可选）实现 SPI 桥（身份/AI/数据源/邮件）
 └── （可选）veloflow.* 配置开关
```

引擎对宿主的全部硬依赖：`DataSource`（一个）+ MyBatis-Plus 类路径（随依赖传递）。
审计填充（created_at 等）默认实现随包提供，宿主自有 `MetaObjectHandler` 时自动让位。

## 二、多数据源宿主（重要）

单数据源宿主零配置。多数据源宿主（如 MySQL 主 + PG 副）需防 mapper 被错误路由：

```yaml
veloflow:
  mapper-scan:
    enabled: false   # 关闭引擎自有 SqlSessionFactory
```

并在**主数据源**的 `@MapperScan` 追加引擎 mapper 包（绑定主 factory）：

```java
@MapperScan(basePackages = {"com.yourhost.mapper", "com.veloflow.engine.persistence.mapper"},
            annotationClass = Mapper.class, sqlSessionFactoryRef = "主factory")
```

> gewu 平台（MySQL 主 + wenshi PG）即此模式——VLF 误路由 PG 会报 relation does not exist。

## 三、SPI 桥接范例

### 3.1 身份（FlowIdentityProvider）

```java
@Component
public class MyIdentityBridge implements FlowIdentityProvider {
    @Override public String currentUserId() { return UserContext.currentUserId(); }
    @Override public String currentUsername() { return UserContext.currentUsername(); }
    @Override public List<String> currentRoles() { return UserContext.get().getRoleCodes(); }  // 角色校验依赖
    @Override public Map<String, String> batchUserNames(Set<String> ids) { ... }
}
```

> 覆写 `currentRoles()` 必须真实返回角色——权限校验（发起/办理矩阵/管理权）都依赖它。
> 角色码大小写敏感匹配，平台大写（如 ADMIN）时管理权判定已做忽略大小写。

### 3.2 AI 桥（FlowAiBridge）——llm/agent/orchestration/knowledge 四节点

```java
@Component
public class MyAiBridge implements FlowAiBridge {
    @Override public LlmResult invokeLlm(LlmSpec spec) { /* 直调宿主 LLM 客户端 */ }
    @Override public AgentResult invokeAgent(AgentSpec spec) { /* 宿主 Agent 运行时 */ }
    @Override public OrchestrationResult invokeOrchestration(String graphId, String input, String wfInstanceId) { ... }
    @Override public KnowledgeResult searchKnowledge(String kbId, String query, int topK) { ... }
}
```

依赖注入建议 `ObjectProvider`（AI 能力可能晚装配，参照 @ConditionalOnBean 在普通组件不可靠的教训）。

### 3.3 数据源 / 邮件桥

`FlowDataSourceBridge`（database 节点，宿主绑定受控只读库 + 只读连接 + 超时）、
`FlowMailBridge`（email 节点，SMTP 配置化）——引擎侧已完成 SQL 形状守卫与可读降级。

## 四、配置项清单

| 配置 | 默认 | 说明 |
|------|------|------|
| `veloflow.rest.enabled` | false | REST API 总开关 |
| `veloflow.webhook.enabled` | false | Webhook 匿名端点开关（token 即凭证，404 语义） |
| `veloflow.webhook.sync-timeout-ms` | 15000 | respond 节点同步返回等待上限 |
| `veloflow.mapper-scan.enabled` | true | 引擎自有 SqlSessionFactory（多数据源置 false） |
| `veloflow.escalate.notify-userids` | 空 | 超时升级通知的管理员（逗号分隔） |
| `veloflow.im.webhooks.<channel>` | 空 | im-notify 渠道 webhook（dingtalk/wecom/feishu，不落流程定义） |
| `veloflow.mail.enabled` | false | email 节点启用（需 spring.mail.*） |
| `veloflow.mail.from` | spring.mail.username | 发件人 |

## 五、版本迁移

- 新接入：执行 `db/init/veloflow_init.sql`（全量基线）
- 已接入升级：按序手工执行 `db/upgrade/V*.sql`
- **铁律：已应用的迁移脚本严禁修改**（Flyway/checksum 校验一致）；变更一律新增版本号

## 六、常见问题

| 现象 | 原因与处理 |
|------|-----------|
| VLF INSERT 报 Unknown column | 迁移链落后（如漏 V64/V73 审计列），按序补 upgrade 脚本 |
| VLF INSERT 走 PG 报 relation does not exist | 多数据源路由错误——见 §二 |
| 触发链路实例缺发起人 | 正常（webhook/定时无登录态，审计记 system） |
| sub-workflow 父实例不推进 | 子实例终态经监听器唤醒；确认父行 child_instance_id 已落库（单实例部署约定） |
| AI/database/email 节点失败 | 对应 SPI 未接入（可读错误含指引），实现桥即可 |
| 发布后改图被拒 | 草稿保护——先 `POST /versions/{v}/rollback` 回草稿 |

## 七、部署边界（当前版本约定）

- 单实例部署：实例推进为 JVM 锁 + DB 状态机（多副本需分布式锁，规划中）
- respond 同步返回为进程内 Future（Webhook 请求与流程须同进程）
- 定时扫描器每分钟 CAS 抢占，多副本重复触发由 CAS 幂等兜底但建议单实例

## 八、发布

```bash
mvn clean deploy   # 发布 jar + sources 到 pom distributionManagement 指定仓库
```

本地仓库验证：

```bash
mvn deploy -DaltReleaseDeploymentRepository=local::default::file:///tmp/repo \
           -DaltSnapshotDeploymentRepository=local::default::file:///tmp/repo
```

> 注意：`-DaltDeploymentRepository` 可能被宿主 settings.xml profile 的
> `altRelease/SnapshotDeploymentRepository` 覆盖（阿里云云效私服等），覆盖同名属性即可。

---
*文档对应 veloflow-engine 1.0.0；节点/端点/SPI 明细以 `veloflow-engine/README.md` 与代码为准。*
