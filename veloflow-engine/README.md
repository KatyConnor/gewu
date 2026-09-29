# Veloflow Engine

独立流程引擎（BPM）——流程编排、人工任务治理、多方式触发、版本快照、权限治理、审计监控。
其他项目**引依赖 + 执行初始化脚本 + 配置开关**三步接入。

```xml
<dependency>
    <groupId>com.veloflow</groupId>
    <artifactId>veloflow-engine</artifactId>
    <version>1.0.0</version>
</dependency>
```

> 零宿主依赖（无任何 com.\* 业务耦合）：Spring Boot 3.2+ / JDK 21 / MyBatis-Plus 3.5.7。
> 引入 jar 即自动装配（`AutoConfiguration.imports`），默认实现开箱即用，SPI 按需扩展。

## 三步接入

### 第 1 步：执行初始化脚本

在宿主**主数据库**执行全量建表脚本：

```
db/init/veloflow_init.sql    （14 张 VLF_ 前缀表）
```

> 已接入系统的版本升级走 `db/upgrade/V*.sql` 手工执行；**已应用的迁移脚本严禁修改**（checksum 校验）。

### 第 2 步：配置

```yaml
spring:
  datasource: # 宿主任意 JDBC 数据源（MySQL 8 验证基线）
    url: jdbc:mysql://localhost:3306/your_db
    username: xxx
    password: xxx

veloflow:
  rest:
    enabled: true                  # REST API 总开关（默认 false）
  webhook:
    enabled: true                  # Webhook 匿名触发端点开关（默认 false）
    sync-timeout-ms: 15000         # respond 节点同步返回等待上限
  mapper-scan:
    enabled: true                  # 单数据源宿主保持默认；多数据源宿主置 false 并在主 @MapperScan 追加
                                   # com.veloflow.engine.persistence.mapper 包
```

### 第 3 步：启动

启动宿主应用即完成——34 种节点、REST API、定时扫描器全部就绪。

## 能力总览（34 种节点，7 大类）

| 分组 | 节点 |
|------|------|
| 触发器 | manual / schedule / webhook（同步返回）/ event / upstream |
| 人工任务 | task、approval（或签/会签/比例 + 驳回回退 + 超时三动作 + 委托） |
| 逻辑控制 | condition / switch / decision / loop / parallel / join / filter |
| AI 能力 | llm / agent / orchestration / knowledge（经 FlowAiBridge SPI） |
| 数据处理 | transform / json-parse / set-variable |
| 集成对接 | http-request（SSRF 防护）/ database（只读守卫）/ email / im-notify |
| 流程控制与终态 | delay / receive-message / respond / event-wait / sub-workflow / counter / return / error-end / terminate-end |

## SPI 扩展点（可选，全部零依赖自动发现）

| SPI | 作用 | 未接入时行为 |
|-----|------|--------------|
| `FlowIdentityProvider` | 用户/角色身份（审计、待办、办理人展示） | 引擎内建默认（system） |
| `FlowAiBridge` | llm/agent/orchestration/knowledge 四类 AI 节点 | AI 节点以可读错误完成 |
| `FlowDataSourceBridge` | database 节点受控只读查询 | database 节点以可读错误完成 |
| `FlowMailBridge` | email 节点 SMTP 发送 | email 节点以可读错误完成 |
| `WorkflowRuntimeListener` | 运行时生命周期回调（引擎内部已注册响应/订阅/子流程联动） | — |

实现任一接口注册为 Spring Bean 即自动接入；`MetaObjectHandler` 默认实现随包提供（宿主自有填充时让位）。

## REST 端点（`veloflow.rest.enabled=true`）

| 端点 | 说明 |
|------|------|
| `POST/GET/PUT/DELETE /api/v1/workflows` | 流程定义 CRUD / 发布 / 归档 |
| `PUT/GET /api/v1/workflows/{id}/graph` | 设计器图保存/回显（biz 坐标系） |
| `GET /api/v1/workflows/{id}/versions`、`POST .../versions/{v}/rollback` | 版本快照与回滚 |
| `GET/PUT /api/v1/workflows/{id}/permissions` | 权限集（START/MANAGE + 节点矩阵） |
| `PUT/GET /api/v1/workflows/{id}/schedule`、`.../webhook` | 定时/Webhook 触发配置 |
| `POST /api/v1/workflows/webhooks/{token}` | Webhook 匿名触发（404 语义；respond 流程 200 同步返回） |
| `POST /api/v1/workflows/events/{eventType}` | 事件交付（唤醒 event-wait / 发起 event-trigger） |
| `POST /api/v1/workflows/instances/{wfId}/start`、`GET .../instances` | 实例发起/列表/详情 |
| `PUT .../instances/{id}/nodes/{nid}/complete`、`.../delegate`、`GET .../my-todos` | 人工办理/委托/待办 |
| `POST .../instances/{id}/messages`、`POST /api/v1/workflows/messages/{key}` | 消息交付（receive-message） |

## 安全基线

- Webhook token 明文仅创建/重置返回一次，库内只存 SM3 哈希；未命中/停用/关开关统一 404
- http-request 出站 SSRF 校验（内网默认拒绝）；database 节点仅 SELECT/WITH + 写关键字/多语句/注释拒绝
- 所有状态变更落 `VLF_WORKFLOW_AUDIT_LOG`（触发链路兜底 system）

## 构建/发布

```
mvn clean package        # 测试 + 构建引擎
mvn deploy               # 发布（私服地址见 pom distributionManagement）
```

详细接入指南（多数据源、SPI 桥接范例、版本迁移、常见问题）：`docs/design/54-veloflow-integration-guide.md`
