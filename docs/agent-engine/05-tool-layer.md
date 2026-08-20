# 05 - 工具体系与安全管线

> 格物 Agent 引擎的工具抽象层设计、安全管线编排、三通道执行与注册机制。

## 1. 概述

工具（Tool）是 Agent 与外部世界交互的能力来源——Agent 通过 LLM 决定调用哪个工具、传入什么参数，框架负责安全地执行工具并将结果回灌给 LLM。格物 Agent 引擎的工具层提供统一的抽象、可插拔的安全管线与三种执行通道（HTTP / MCP / 沙箱），并通过双轨注册（代码 + 配置源 SPI）实现灵活的工具管理。

```
com.gewu.agent.engine.tool
├── Tool                 // 工具接口
├── ToolProvider         // @ToolProvider 代码注册注解
├── ToolRegistry         // 代码级工具注册中心
├── ToolConfigSource      // 外部工具配置源 SPI
├── NoOpToolConfigSource  // 默认空实现
├── ToolExecutor         // 工具执行器(安全管线编排)
├── ToolContext           // 执行上下文
├── ToolResult           // 执行结果
└── security
    ├── SecurityCheck    // 安全检查接口
    ├── SecurityChain    // 安全检查链
    ├── SchemaValidator  // JSON Schema 参数校验(默认)
    ├── CodeScanner      // 代码扫描 SPI
    ├── DefaultCodeScanner// 默认实现(Python/Shell 危险操作扫描)
    └── SsrfValidator    // SSRF 防护
```

## 2. Tool 接口

```java
public interface Tool {
    /** 工具定义：名称 / 描述 / JSON Schema 参数 */
    ToolDefinition getDefinition();

    /** 执行工具 */
    ToolResult invoke(String arguments, ToolContext context);
}
```

`ToolDefinition` 位于 `llm.model` 包（LLM 请求体构建也用它）：

```java
public class ToolDefinition {
    private String name;         // 工具名(唯一标识)
    private String description;  // 工具描述(LLM 据此决定是否调用)
    private String parameters;   // JSON Schema 格式参数定义
}
```

## 3. 代码级注册：@ToolProvider

实现 `Tool` 接口并标注 `@ToolProvider`，框架启动时自动扫描注册：

```java
@ToolProvider(category = "SEARCH")
public class WebSearchTool implements Tool {

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name("web_search")
            .description("搜索网络获取最新信息")
            .parameters("""
                {
                  "type": "object",
                  "properties": {
                    "query": {"type": "string", "description": "搜索关键词"}
                  },
                  "required": ["query"]
                }
                """)
            .build();
    }

    @Override
    public ToolResult invoke(String arguments, ToolContext context) {
        // 解析参数、执行搜索、返回结果
        return ToolResult.success("搜索结果...", 0);
    }
}
```

`@ToolProvider` 本身是 `@Component` 的派生注解，标注的类自动成为 Spring Bean。

## 4. ToolRegistry 注册中心

```java
public class ToolRegistry {
    public ToolRegistry(List<Tool> toolBeans) { ... }
    public Tool getTool(String name);              // 按名解析
    public List<ToolDefinition> listDefinitions(); // 全部定义
    public boolean hasTool(String name);
}
```

`ToolRegistry` 启动时收集所有 `@ToolProvider` 标注的 Bean，按 `getDefinition().getName()` 索引。代码工具优先于配置工具被执行。

## 5. 外部配置源：ToolConfigSource SPI

对于无法代码注册的工具（DB 配置的 HTTP 端点 / MCP 工具 / 沙箱代码执行），通过 SPI 从外部加载：

```java
public interface ToolConfigSource {
    List<ToolConfig> loadTools();
    default List<ToolConfig> loadToolsByAgent(String agentId) { ... }
}
```

`ToolConfig` 描述一个外部工具的完整配置：

| 字段 | 类型 | 说明 |
|------|------|------|
| `toolName` | String | 工具名(唯一标识) |
| `description` | String | 描述 |
| `requestSchema` | String | JSON Schema 参数定义 |
| `toolType` | String | 工具类型：`http` / `mcp` / `code_execute` |
| `endpoint` | String | HTTP 工具的端点地址 |
| `mcpServerId` | String | MCP 工具关联的 Server ID |
| `timeoutMs` | Integer | 执行超时(毫秒) |
| `status` | Integer | 1 启用 / 0 禁用 |

未提供实现时使用 `NoOpToolConfigSource`（返回空），此时仅代码注册的工具可用。

## 6. ToolExecutor 执行流程

`ToolExecutor` 是工具执行的核心，统一编排安全管线与三通道执行：

```
工具调用请求
    │
    ▼
┌─────────────────────────────────┐
│ 1. SecurityChain 安全检查       │  ← SchemaValidator 参数校验
│    (可插拔: 可注入多个 Check)    │  ← 使用方可扩展自定义 Check
└──────────────┬──────────────────┘
               ▼
┌─────────────────────────────────┐
│ 2. PermissionService 权限评估   │  ← allow / deny / ask
└──────────────┬──────────────────┘
               ▼
┌─────────────────────────────────┐
│ 3. 执行分派                      │
│   ├─ 代码工具(ToolRegistry 命中) │  ← 直接 invoke
│   ├─ http  → executeViaHttp      │  ← SsrfValidator 校验
│   ├─ mcp   → executeViaMcp       │  ← McpServerManager
│   └─ code  → executeInSandbox    │  ← CodeScanner 扫描
└──────────────┬──────────────────┘
               ▼
┌─────────────────────────────────┐
│ 4. 输出截断                      │  ← maxOutputSize (默认 10KB)
└──────────────┬──────────────────┘
               ▼
┌─────────────────────────────────┐
│ 5. AuditService 审计记录        │  ← 记录 tool/user/success/duration
└─────────────────────────────────┘
```

`ToolExecutor.execute()` 签名：

```java
public ToolResult execute(String toolName, String arguments,
                          ToolContext context, ToolConfig config);
```

- 代码工具优先：若 `ToolRegistry.getTool(toolName)` 命中，直接调用 `Tool.invoke`，跳过通道分派。
- 配置工具按 `ToolConfig.toolType` 分派到对应通道。
- `config` 可为 null（纯代码工具场景）。

## 7. 安全管线详解

### 7.1 SecurityCheck 接口与 SecurityChain

```java
public interface SecurityCheck {
    void check(String toolName, String arguments,
               ToolContext context, ToolConfig config);
}

public class SecurityChain implements SecurityCheck {
    private final List<SecurityCheck> checks;
    public void check(...) { // 按序执行
        for (SecurityCheck c : checks) c.check(...);
    }
}
```

框架默认装配 `SchemaValidator`。使用方可注册自定义 `SecurityCheck` Bean（如内容审核、敏感信息脱敏），自动加入 `SecurityChain`。

### 7.2 SchemaValidator — JSON Schema 参数校验

```java
public class SchemaValidator implements SecurityCheck {
    public ValidationResult validate(String schema, String arguments);
    public void check(String toolName, String arguments,
                      ToolContext context, ToolConfig config);
}
```

支持 `object / array / string / number / integer / boolean` 类型校验与 `required` 必填校验。校验失败抛出 `AgentEngineException(code=SCHEMA_INVALID)`。

### 7.3 SsrfValidator — SSRF 防护

```java
public class SsrfValidator {
    public SsrfValidator(String allowedHostsConfig);  // 逗号分隔白名单
    public void validate(URI uri);                    // 校验失败抛 SSRF_BLOCKED
}
```

防护策略：
- 仅允许 `http` / `https` 协议
- 默认**拒绝所有外部主机**，须显式配置 `agent.engine.tool.allowed-hosts` 白名单
- 禁止访问内网地址（回环 / 站点本地 / 链路本地）
- HTTP 重定向逐跳校验目标地址

### 7.4 CodeScanner — 沙箱代码扫描

```java
public interface CodeScanner {
    void scan(String code, String language);
}
```

`DefaultCodeScanner` 拦截危险操作：

| 语言 | 拦截内容 |
|------|---------|
| Python | `os.system` / `os.popen` / `subprocess.*` / `os.remove` / `shutil.rmtree` / `socket.*` / `requests.*` / `eval` / `exec` / `__import__` / `os.setuid` |
| Shell | `rm -rf` / `mkfs` / `shutdown` / `chmod 777` / `wget` / `curl` / `iptables` 等 |
| 通用 | `/etc/passwd` / `/etc/shadow` / `/root` / 访问敏感环境变量(PASSWORD/SECRET/API_KEY/TOKEN) |

使用方可实现 `CodeScanner` 接口注册自定义扫描规则。

## 8. 三通道执行

### 8.1 HTTP 通道

```java
private String executeViaHttp(ToolConfig tool, String arguments, ToolContext context);
```

- 从 `ToolConfig.endpoint` 获取目标地址
- `SsrfValidator.validate()` 校验 URI
- 构造 POST 请求发送，处理重定向（限 `maxRedirects` 次）
- 超时取 `context.timeout` 或 `ToolConfig.timeoutMs`

### 8.2 MCP 通道

```java
private String executeViaMcp(ToolConfig tool, String arguments);
```

- 从 `ToolConfig.mcpServerId` 获取 MCP 客户端连接（`McpServerManager.getOrConnect`）
- 调用 `McpClient.callTool(toolName, arguments)`
- 失败抛 `TOOL_EXECUTION_FAILED`

### 8.3 沙箱通道

```java
private String executeInSandbox(ToolConfig tool, String arguments, ToolContext context);
```

- 解析参数中的 `language` 和 `code` 字段
- `CodeScanner.scan()` 扫描危险操作
- 通过 `SandboxExecutor SPI` 执行（使用方需提供实现，默认 `NoOpSandboxExecutor` 抛 `SANDBOX_NOT_CONFIGURED`）
- 拼接 stdout / stderr 返回

## 9. ToolContext 与 ToolResult

### ToolContext

```java
public class ToolContext {
    private String userId;
    private String sessionId;
    private String agentId;
    private int timeout;          // 执行超时(秒)
    private boolean sandboxEnabled;
    private String sandboxImage;
    private String projectId;
}
```

### ToolResult

```java
public class ToolResult {
    private boolean success;
    private String output;    // 输出内容
    private String error;     // 错误信息
    private long duration;    // 耗时(ms)
    private boolean truncated; // 输出是否被截断

    public static ToolResult success(String output, long duration);
    public static ToolResult failure(String error, long duration);
}
```

## 10. 配置项

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `agent.engine.tool.allowed-hosts` | `""` | HTTP 工具允许访问的主机白名单(逗号分隔)，空=拒绝全部 |
| `agent.engine.tool.max-redirects` | `5` | HTTP 工具最大重定向次数 |
| `agent.engine.tool.max-output-size` | `10240` | 工具输出最大字节数 |
| `agent.engine.tool.default-timeout-seconds` | `30` | 默认工具执行超时(秒) |

## 11. 自定义工具完整示例

```java
// 1. 代码注册工具
@ToolProvider(category = "DB")
public class QuerySchemaTool implements Tool {
    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
            .name("query_schema")
            .description("查询数据库表结构")
            .parameters("""
                {"type":"object","properties":{"table":{"type":"string"}},"required":["table"]}
                """)
            .build();
    }

    @Override
    public ToolResult invoke(String arguments, ToolContext ctx) {
        // 执行查询...
        return ToolResult.success("表结构: ...", 15);
    }
}

// 2. 自定义安全检查
@Component
public class SensitiveDataCheck implements SecurityCheck {
    @Override
    public void check(String toolName, String arguments, ToolContext ctx, ToolConfig config) {
        if (arguments.contains("password") || arguments.contains("secret")) {
            throw AgentEngineException.of("SENSITIVE_DATA", "工具参数含敏感信息");
        }
    }
}
// SensitiveDataCheck 自动加入 SecurityChain，在 SchemaValidator 之后执行

// 3. 工具配置源(从 DB 加载 http/mcp/code_execute 工具)
@Component
public class DbToolConfigSource implements ToolConfigSource {
    @Override
    public List<ToolConfig> loadTools() { /* 从 agent_tool 表加载 */ }
}
```

## 12. 小结

格物 Agent 引擎的工具层通过 **双轨注册 + 可插拔安全管线 + 三通道执行** 实现了灵活而安全的工具管理。代码注册适合内置能力，配置源适合运行时动态工具；安全管线层层防护（参数校验→权限→SSRF→代码扫描→审计），可按需扩展。详见 [10-spi-reference.md](10-spi-reference.md) 的 SPI 规范与 [12-extension-guide.md](12-extension-guide.md) 的扩展指南。