# 06 · MCP 集成

> Model Context Protocol（MCP）是连接 LLM 与外部工具/数据源的开放协议。格物 Agent 引擎在 `com.gewu.agent.engine.mcp` 包提供完整的 MCP 客户端抽象、两种传输实现与连接管理，使 Agent 可以像调用本地工具一样调用任意 MCP 服务器暴露的能力。

[![MCP](https://img.shields.io/badge/Protocol-2024--11--05-blue)]()

## 6.1 设计目标

| 目标 | 说明 |
|------|------|
| **协议兼容** | 遵循 MCP `2024-11-05` 协议版本，支持 `initialize` 握手、`tools/list`、`tools/call` |
| **双传输** | Stdio（子进程本地通信）与 SSE / streamable_http（远程 HTTP）两种传输 |
| **连接复用** | `McpServerManager` 缓存已握手连接，避免重复 `initialize` |
| **配置解耦** | 服务器定义通过 `McpServerConfigSource` SPI 加载，框架不绑定数据库 |
| **零默认可用** | 提供 `NoOpMcpServerConfigSource`，未实现 SPI 时不报错，MCP 工具静默不可用 |

## 6.2 架构总览

```
                      ┌──────────────────────────────────────────────┐
   ToolExecutor       │                McpServerManager               │
   (toolType=mcp) ───►│  getOrConnect(serverId) / listTools(serverId) │
                      └───────────────┬──────────────┬───────────────┘
                                      │              │
                          createAndConnect           缓存 ConcurrentHashMap<id,McpClient>
                                      │
                ┌─────────────────────┴─────────────────────┐
                │  McpServerConfigSource SPI  (loadServer)   │  ◄── 使用方对接 DB/配置中心
                └─────────────────────┬─────────────────────┘
                                      │ McpServerDescriptor
                                      ▼
        ┌──────────── transport="stdio" ───────────┐   ┌──────── transport="sse"/"streamable_http" ──────┐
        │            StdioMcpClient                 │   │              SseMcpClient                       │
        │  ProcessBuilder → 子进程 stdin/stdout     │   │  java.net.http.HttpClient → POST JSON-RPC      │
        │  initialize / tools/list / tools/call     │   │  initialize / tools/list / tools/call           │
        └──────────────────────────────────────────┘   └────────────────────────────────────────────────-┘
                                      │ JSON-RPC 2.0 over stdio / HTTP
                                      ▼
                          ┌─────────────────────────┐
                          │   外部 MCP 服务器进程    │
                          │  (filesystem / git / db) │
                          └─────────────────────────┘
```

## 6.3 核心接口：McpClient

`McpClient` 是所有 MCP 传输客户端的统一抽象，位于 `com.gewu.agent.engine.mcp`：

```java
package com.gewu.agent.engine.mcp;

import java.util.List;

public interface McpClient {

    /** 建立连接并完成 initialize 握手 */
    void connect() throws Exception;

    /** 关闭连接 */
    void close();

    /** 是否已连接 */
    boolean isConnected();

    /** 列出服务器提供的工具 */
    List<McpToolDefinition> listTools();

    /** 调用工具 */
    McpToolResult callTool(String toolName, String arguments);
}
```

五个方法对应 MCP 客户端的生命周期：`connect()` 完成协议握手；`listTools()` 映射为 `tools/list`；`callTool(name, arguments)` 映射为 `tools/call`，`arguments` 为 JSON 字符串。`McpToolDefinition`（`name` / `description` / `inputSchema`）与 `McpToolResult`（`success` / `output` / `error`）是框架内的工具元数据与调用结果载体。

## 6.4 StdioMcpClient —— 子进程本地通信

`StdioMcpClient` 通过 `ProcessBuilder` 启动 MCP 服务器子进程，以子进程的 stdin/stdout 作为 JSON-RPC 传输管道。构造签名为：

```java
public StdioMcpClient(String command, List<String> args, Map<String, String> env)
```

握手与通信流程：

```
StdioMcpClient.connect()
   │
   ├─► ProcessBuilder(command).command().addAll(args)  + 注入 env
   │      redirectErrorStream(false) → process.start()
   │      stdin  = BufferedWriter(process.getOutputStream())
   │      stdout = BufferedReader(process.getInputStream())
   │
   ├─► initialize():
   │      params.protocolVersion = "2024-11-05"
   │      params.clientInfo = { name: "agent-engine", version: "1.0.0" }
   │      sendRequest("initialize", params) ──► stdout.readLine() 解析 result
   │
   └─► initialized = true

sendRequest(method, params):     ┌──────────────────────────────┐
  写入 JSON:                     │ {                            │
  {"jsonrpc":"2.0","id":N,        │   "jsonrpc":"2.0",          │
   "method":"tools/call",         │   "id":3,                   │
   "params":{...}}\n             │   "method":"tools/list",     │
                                 │   "params":{} }              │
  stdout.readLine() → 解析       │ ───────────────────────►    │ 子进程
  有 "error" → 抛 IOException     │ ◄───────────────────────    │ MCP Server
  否则返回 result 节点            │ { "result": { ... } }        │
                                 └──────────────────────────────┘
```

`close()` 依次关闭 stdin/stdout 并 `process.destroyForcibly()`；`isConnected()` 校验 `initialized && process != null && process.isAlive()`。

## 6.5 SseMcpClient —— HTTP 传输

`SseMcpClient` 走 HTTP POST 与远程 MCP 服务器通信，适用于部署在远端的 MCP 服务。构造签名为：

```java
public SseMcpClient(String url)
```

其内部使用 `HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()`。`sendRequest` 将同一份 JSON-RPC 请求体以 POST 发往 `endpointUrl != null ? endpointUrl : serverUrl`，超时 30 秒，`Content-Type: application/json`，响应体解析同 Stdio（有 `error` 节点则抛 `IOException`，否则返回 `result`）。`initialize` 与 `tools/list`、`tools/call` 的参数构造逻辑与 `StdioMcpClient` 完全一致，差异仅在传输通道。

## 6.6 McpServerManager —— 连接管理与缓存

`McpServerManager` 是 MCP 能力的对外门面，负责按需建连、缓存复用与断连。核心方法：

| 方法 | 作用 |
|------|------|
| `McpServerManager(McpServerConfigSource configSource)` | 注入配置源 SPI |
| `McpClient getOrConnect(String serverId)` | 取缓存命中；未命中则 `createAndConnect` 并缓存 |
| `void disconnect(String serverId)` | 断开并移除单个连接 |
| `void disconnectAll()` | 断开所有连接 |
| `List<McpToolDefinition> listTools(String serverId)` | 连接后调用 `listTools()` |

`createAndConnect` 的核心逻辑——依据 `McpServerDescriptor.transport` 分派传输实现：

```java
String transport = server.getTransport() != null ? server.getTransport() : "stdio";
if ("stdio".equals(transport)) {
    List<String> args = parseArgs(server.getArgs());       // JSON 数组字符串 → List
    Map<String, String> env = parseEnv(server.getEnv());   // JSON 对象字符串 → Map
    client = new StdioMcpClient(server.getCommand(), args, env);
} else if ("sse".equals(transport) || "streamable_http".equals(transport)) {
    client = new SseMcpClient(server.getUrl());
} else {
    throw new IllegalArgumentException("不支持的传输方式: " + transport);
}
client.connect();   // 失败抛 RuntimeException
```

`args` 与 `env` 字段在 `McpServerDescriptor` 中以 JSON 字符串存储，由 `ObjectMapper.readValue` 反序列化。客户端缓存为 `ConcurrentHashMap<String, McpClient>`，`getOrConnect` 用 `computeIfAbsent` 保证并发安全建连。

## 6.7 McpServerDescriptor —— 配置模型

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `String` | 服务器标识 |
| `name` | `String` | 名称 |
| `transport` | `String` | 传输方式：`stdio` / `sse` / `streamable_http` |
| `command` | `String` | stdio 启动命令 |
| `args` | `String` | stdio 参数（JSON 数组字符串，如 `["--read-only"]`） |
| `env` | `String` | stdio 环境变量（JSON 对象字符串，如 `{"GITHUB_TOKEN":"xxx"}`） |
| `url` | `String` | SSE / HTTP 端点地址 |
| `status` | `Integer` | 1 启用 / 0 禁用 |

## 6.8 McpServerConfigSource SPI

```java
package com.gewu.agent.engine.mcp;

import java.util.List;

public interface McpServerConfigSource {
    McpServerDescriptor loadServer(String serverId);
    default List<McpServerDescriptor> loadAllServers() { return List.of(); }
}
```

| 维度 | 说明 |
|------|------|
| **方法契约** | `loadServer` 不存在返回 `null`；`loadAllServers` 默认返回空 |
| **实现要求** | 使用方从数据库 / 配置中心 / 文件加载 `McpServerDescriptor`，`apiKey` 等敏感字段应在实现内解密 |
| **NoOp 默认** | `NoOpMcpServerConfigSource`：`loadServer` 返回 `null`，`loadAllServers` 返回 `List.of()` |
| **业务适配备注** | 格物平台对应 `mcp_server` 表，映射 `id/transport/command/args/env/url/status` 字段 |

`AgentEngineAutoConfiguration` 已装配 `NoOpMcpServerConfigSource` 与 `McpServerManager` Bean，使用方注册任意实现为 Spring Bean 即可覆盖。

## 6.9 使用方实现示例 —— 从数据库加载 MCP 服务器

```java
package com.gewu.app.agent.spi;

import com.gewu.agent.engine.mcp.McpServerConfigSource;
import com.gewu.agent.engine.mcp.McpServerDescriptor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class DbMcpServerConfigSource implements McpServerConfigSource {

    private final McpServerMapper mapper;   // 使用方 MyBatis Mapper

    public DbMcpServerConfigSource(McpServerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public McpServerDescriptor loadServer(String serverId) {
        McpServerDO entity = mapper.selectById(serverId);
        if (entity == null || entity.getStatus() == null || entity.getStatus() != 1) {
            return null;   // 不存在或禁用即返回 null
        }
        return McpServerDescriptor.builder()
                .id(entity.getId())
                .name(entity.getName())
                .transport(entity.getTransport())    // stdio / sse
                .command(entity.getCommand())
                .args(entity.getArgs())              // JSON 数组字符串
                .env(entity.getEnv())                // JSON 对象字符串
                .url(entity.getUrl())
                .status(entity.getStatus())
                .build();
    }

    @Override
    public List<McpServerDescriptor> loadAllServers() {
        return mapper.selectAllEnabled().stream()
                .map(e -> McpServerDescriptor.builder()
                        .id(e.getId()).name(e.getName()).transport(e.getTransport())
                        .command(e.getCommand()).args(e.getArgs()).env(e.getEnv())
                        .url(e.getUrl()).status(e.getStatus()).build())
                .toList();
    }
}
```

## 6.10 调用 MCP 工具

MCP 工具在框架内通过 `ToolConfig.toolType = "mcp"` + `mcpServerId` 声明。`ToolExecutor` 执行此类工具时，委托 `McpServerManager.getOrConnect(mcpServerId)` 获取客户端，再调用 `client.callTool(toolName, arguments)`：

```java
McpClient client = mcpServerManager.getOrConnect("github-mcp");
List<McpToolDefinition> tools = client.listTools();          // 发现工具
McpToolResult result = client.callTool("create_issue",
        "{\"title\":\"修复登录Bug\",\"body\":\"...\"}");    // 调用工具
if (result.isSuccess()) {
    // result.getOutput() 为 MCP 返回的 text 内容拼接
}
```

## 6.11 配置示例

`application.yml`（MCP 本身无需专门配置项，由 SPI 提供服务器定义；以下为关联的沙箱/工具项）：

```yaml
agent:
  engine:
    tool:
      allowed-hosts: ""          # HTTP 工具主机白名单
      default-timeout-seconds: 30
      max-output-size: 10240     # 输出最大字节数
```

数据库 `mcp_server` 示例行（stdio 传输，连接到本地的 GitHub MCP）：

| id | name | transport | command | args | url | status |
|----|------|----------|---------|------|-----|--------|
| `github-mcp` | GitHub MCP | `stdio` | `npx` | `["@modelcontextprotocol/server-github"]` | (null) | 1 |
| `remote-search` | 远程检索 | `sse` | (null) | (null) | `https://mcp.example.com/sse` | 1 |

## 6.12 小结

MCP 集成将 Agent 的工具域从"项目内代码工具/HTTP 工具"扩展到"任意 MCP 生态服务器"。`McpClient` 抽象屏蔽传输差异，`McpServerManager` 提供连接复用，`McpServerConfigSource` SPI 将服务器定义权交还使用方。与[05 工具层](05-tool-layer.md)的安全管线组合：MCP 工具调用同样经过 Schema 校验、权限评估与审计，实现"统一通道、统一治理"。