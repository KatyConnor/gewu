package com.gewu.agent.engine.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.mcp.McpClient;
import com.gewu.agent.engine.mcp.McpServerManager;
import com.gewu.agent.engine.mcp.McpToolResult;
import com.gewu.agent.engine.spi.AuditService;
import com.gewu.agent.engine.spi.ExecResult;
import com.gewu.agent.engine.spi.PermissionResult;
import com.gewu.agent.engine.spi.PermissionService;
import com.gewu.agent.engine.spi.SandboxExecutor;
import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.security.SchemaValidator;
import com.gewu.agent.engine.tool.security.SecurityChain;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ToolExecutor} 五段管线测试：安全链 -> 权限 -> 执行分派 -> 截断 -> 审计。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("工具执行器")
class ToolExecutorTest {

    @Mock
    private PermissionService permissionService;

    @Mock
    private AuditService auditService;

    @Mock
    private SandboxExecutor sandboxExecutor;

    @Mock
    private McpServerManager mcpServerManager;

    private HttpServer httpServer;
    private String httpEndpoint;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 代码工具：直接返回固定输出 */
    private final Tool echoTool = new Tool() {
        @Override
        public ToolDefinition getDefinition() {
            return ToolDefinition.builder().name("echo").description("回声").build();
        }

        @Override
        public ToolResult invoke(String arguments, ToolContext context) {
            return ToolResult.success("echo:" + arguments, 5);
        }
    };

    @BeforeEach
    void setUp() {
        lenient().when(permissionService.evaluate(any(), anyString(), any()))
                .thenReturn(PermissionResult.allow());
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private ToolExecutor executor(ToolRegistry registry) {
        // 安全链只装 SchemaValidator（无 SsrfValidator），便于本地 HTTP 端点测试
        return new ToolExecutor(registry, new SecurityChain(List.of(new SchemaValidator(objectMapper))),
                permissionService, auditService, sandboxExecutor, mcpServerManager,
                objectMapper, 10240, 5, 30);
    }

    private ToolContext context() {
        return ToolContext.builder().userId("u1").agentId("a1").sessionId("s1").timeout(5).build();
    }

    private void startHttpServer(String path, int status, String body) throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (status == 302) {
                exchange.getResponseHeaders().set("Location", "/final");
            }
            exchange.sendResponseHeaders(status, status >= 300 && status < 400 ? -1 : bytes.length);
            if (status < 300 || status >= 400) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        });
        httpServer.start();
        httpEndpoint = "http://127.0.0.1:" + httpServer.getAddress().getPort() + path;
    }

    @Test
    @DisplayName("代码工具优先：注册表命中直接执行并审计")
    void codeToolPriority() {
        ToolResult result = executor(new ToolRegistry(List.of(echoTool)))
                .execute("echo", "hello", context(), null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("echo:hello");
        verify(auditService).recordToolExecution(eq("u1"), eq("a1"), eq("echo"), eq(true), anyLong());
    }

    @Test
    @DisplayName("权限拒绝：失败返回并审计失败")
    void permissionDeniedFails() {
        when(permissionService.evaluate(any(), eq("echo"), any()))
                .thenReturn(PermissionResult.deny("无权限"));

        ToolResult result = executor(new ToolRegistry(List.of(echoTool)))
                .execute("echo", "x", context(), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("权限拒绝").contains("无权限");
        verify(auditService).recordToolExecution(eq("u1"), eq("a1"), eq("echo"), eq(false), anyLong());
    }

    @Test
    @DisplayName("需要审批（ask）：同样失败返回")
    void permissionAskFails() {
        when(permissionService.evaluate(any(), eq("echo"), any()))
                .thenReturn(PermissionResult.ask("敏感操作"));

        ToolResult result = executor(new ToolRegistry(List.of(echoTool)))
                .execute("echo", "x", context(), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("需要用户确认");
    }

    @Test
    @DisplayName("工具不存在且无配置：TOOL_NOT_FOUND")
    void toolNotFound() {
        ToolResult result = executor(new ToolRegistry(List.of()))
                .execute("ghost", "{}", context(), null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("工具不存在且无配置");
        verify(auditService).recordToolExecution(any(), any(), eq("ghost"), eq(false), anyLong());
    }

    @Test
    @DisplayName("HTTP 工具执行成功：返回响应体并审计")
    void httpToolExecution() throws Exception {
        startHttpServer("/api", 200, "{\"result\":\"ok\"}");
        ToolConfig config = ToolConfig.builder()
                .toolName("http_tool").toolType("http").endpoint(httpEndpoint).build();

        ToolResult result = executor(new ToolRegistry(List.of()))
                .execute("http_tool", "{\"q\":\"test\"}", context(), config);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("ok");
        assertThat(result.isTruncated()).isFalse();
        verify(auditService).recordToolExecution(eq("u1"), eq("a1"), eq("http_tool"), eq(true), anyLong());
    }

    @Test
    @DisplayName("HTTP 重定向逐跳跟随且受次数上限约束")
    void httpRedirectLimit() throws Exception {
        startHttpServer("/api", 302, "");
        // /api 302 -> /final；同一 server 上补 /final 上下文
        httpServer.createContext("/final", exchange -> {
            byte[] bytes = "final-ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        ToolConfig config = ToolConfig.builder()
                .toolName("http_tool").toolType("http").endpoint(httpEndpoint).build();

        // maxRedirects=2：允许一次跳转，成功
        ToolResult ok = executor(new ToolRegistry(List.of()))
                .execute("http_tool", "{}", context(), config);
        assertThat(ok.isSuccess()).isTrue();
        assertThat(ok.getOutput()).isEqualTo("final-ok");

        // maxRedirects=0：首次重定向即失败
        ToolExecutor zeroRedirect = new ToolExecutor(new ToolRegistry(List.of()),
                new SecurityChain(List.of()), permissionService, auditService, sandboxExecutor,
                mcpServerManager, objectMapper, 10240, 0, 30);
        ToolResult blocked = zeroRedirect.execute("http_tool", "{}", context(), config);
        assertThat(blocked.isSuccess()).isFalse();
        assertThat(blocked.getError()).contains("重定向次数超过上限");
    }

    @Test
    @DisplayName("输出截断：超过 maxOutputSize 时截断并标记")
    void outputTruncation() {
        ToolExecutor smallLimit = new ToolExecutor(new ToolRegistry(List.of(echoTool)),
                new SecurityChain(List.of()), permissionService, auditService, sandboxExecutor,
                mcpServerManager, objectMapper, 16, 5, 30);
        // 代码工具路径不截断（原样返回），截断仅作用于 HTTP/MCP/沙箱配置路径 -> 用沙箱路径验证
        ToolExecutor sandboxExecutorInstance = new ToolExecutor(new ToolRegistry(List.of()),
                new SecurityChain(List.of()), permissionService, auditService, sandboxExecutor,
                mcpServerManager, objectMapper, 16, 5, 30);
        when(sandboxExecutor.executeCode(anyString(), anyString(), anyInt()))
                .thenReturn(ExecResult.builder().success(true).stdout("x".repeat(100)).build());

        ToolConfig config = ToolConfig.builder()
                .toolName("code_tool").toolType("code_execute").build();
        ToolContext ctx = ToolContext.builder()
                .userId("u1").agentId("a1").timeout(5).sandboxEnabled(true).build();

        ToolResult result = sandboxExecutorInstance.execute("code_tool", "{\"code\":\"print(1)\"}", ctx, config);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isTruncated()).isTrue();
        assertThat(result.getOutput()).hasSize(16);

        // 对照：代码工具路径不受截断影响
        ToolResult echo = smallLimit.execute("echo", "hello", context(), null);
        assertThat(echo.isSuccess()).isTrue();
        assertThat(echo.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("沙箱执行：从参数提取 language/code 并拼接 stdout/stderr")
    void sandboxExecution() {
        when(sandboxExecutor.executeCode(eq("python"), eq("print('hi')"), anyInt()))
                .thenReturn(ExecResult.builder().success(true)
                        .stdout("hi").stderr("warn").build());
        ToolConfig config = ToolConfig.builder()
                .toolName("code_tool").toolType("code_execute").build();
        ToolContext ctx = ToolContext.builder()
                .userId("u1").agentId("a1").timeout(5).sandboxEnabled(true).build();

        ToolResult result = executor(new ToolRegistry(List.of()))
                .execute("code_tool", "{\"language\":\"python\",\"code\":\"print('hi')\"}", ctx, config);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("hi\n[stderr] warn");
    }

    @Test
    @DisplayName("MCP 工具执行：经 McpServerManager 调用并透传输出")
    void mcpExecution() throws Exception {
        McpClient mcpClient = mock(McpClient.class);
        when(mcpServerManager.getOrConnect("server-1")).thenReturn(mcpClient);
        when(mcpClient.callTool(eq("remote_tool"), anyString()))
                .thenReturn(McpToolResult.builder().success(true).output("mcp-result").build());
        ToolConfig config = ToolConfig.builder()
                .toolName("remote_tool").toolType("mcp").mcpServerId("server-1").build();

        ToolResult result = executor(new ToolRegistry(List.of()))
                .execute("remote_tool", "{}", context(), config);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("mcp-result");
    }

    @Test
    @DisplayName("MCP 工具失败：结果标记失败并审计")
    void mcpFailureAudited() throws Exception {
        McpClient mcpClient = mock(McpClient.class);
        when(mcpServerManager.getOrConnect("server-1")).thenReturn(mcpClient);
        when(mcpClient.callTool(anyString(), anyString()))
                .thenReturn(McpToolResult.builder().success(false).error("工具执行异常").build());
        ToolConfig config = ToolConfig.builder()
                .toolName("remote_tool").toolType("mcp").mcpServerId("server-1").build();

        ToolResult result = executor(new ToolRegistry(List.of()))
                .execute("remote_tool", "{}", context(), config);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("工具执行异常");
        verify(auditService).recordToolExecution(any(), any(), eq("remote_tool"), eq(false), anyLong());
    }

    @Test
    @DisplayName("安全链拦截（Schema 校验失败）：fail-fast 抛异常且不进入执行/审计")
    void securityChainBlocksExecution() {
        ToolConfig config = ToolConfig.builder()
                .toolName("http_tool").toolType("http").endpoint("https://x.example.com")
                .requestSchema("{\"type\":\"object\",\"required\":[\"city\"]}").build();

        // 安全检查在 try 块之前，违规直接抛 AgentEngineException（fail-fast 设计）
        assertThatThrownBy(() -> executor(new ToolRegistry(List.of()))
                .execute("http_tool", "{}", context(), config))
                .isInstanceOf(com.gewu.agent.engine.AgentEngineException.class)
                .hasMessageContaining("参数校验失败");

        verify(sandboxExecutor, never()).executeCode(anyString(), anyString(), anyInt());
        verify(auditService, never()).recordToolExecution(any(), any(), anyString(), anyBoolean(), anyLong());
    }
}
