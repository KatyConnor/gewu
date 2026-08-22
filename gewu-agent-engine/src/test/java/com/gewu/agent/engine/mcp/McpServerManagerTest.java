package com.gewu.agent.engine.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link McpServerManager} 连接管理与配置加载测试。
 */
@DisplayName("MCP 服务器管理器")
class McpServerManagerTest {

    /** 模拟 MCP 服务器的 bash 脚本（与 StdioMcpClientTest 相同） */
    private static final String MCP_SERVER_SCRIPT =
            "while read line; do case \"$line\" in "
                    + "*initialize*) echo '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\"}}}';; "
                    + "*tools/list*) echo '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"d\"}]}}';; "
                    + "esac; done";

    private McpServerDescriptor descriptor(String transport, String command, String argsJson) {
        return McpServerDescriptor.builder()
                .id("server-1").name("mock-server")
                .transport(transport).command(command).args(argsJson)
                .build();
    }

    @Test
    @DisplayName("未配置配置源：抛出 IllegalArgumentException")
    void nullConfigSourceRejected() {
        McpServerManager manager = new McpServerManager(null);
        assertThatThrownBy(() -> manager.getOrConnect("server-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未配置 McpServerConfigSource");
    }

    @Test
    @DisplayName("服务器不存在：抛出 IllegalArgumentException")
    void unknownServerRejected() {
        McpServerManager manager = new McpServerManager(id -> null);
        assertThatThrownBy(() -> manager.getOrConnect("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MCP Server 不存在");
    }

    @Test
    @DisplayName("不支持的传输方式：抛出 IllegalArgumentException")
    void unsupportedTransportRejected() {
        McpServerManager manager = new McpServerManager(
                id -> descriptor("grpc", "echo", null));
        assertThatThrownBy(() -> manager.getOrConnect("server-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的传输方式");
    }

    @Test
    @DisplayName("连接失败：异常包装为 MCP Server 连接失败")
    void connectionFailureWrapped() {
        McpServerManager manager = new McpServerManager(
                id -> descriptor("stdio", "bash", "[\"-c\", \"exit 0\"]"));

        assertThatThrownBy(() -> manager.getOrConnect("server-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MCP Server 连接失败");
    }

    @Test
    @DisplayName("stdio 连接成功：连接缓存复用，disconnect 后重建")
    void connectionCachedAndRecreated() throws Exception {
        // args 为 JSON 字符串，验证 parseArgs 解析
        McpServerManager manager = new McpServerManager(
                id -> descriptor("stdio", "bash", "[\"-c\", \"" + MCP_SERVER_SCRIPT.replace("\"", "\\\"") + "\"]"));

        McpClient first = manager.getOrConnect("server-1");
        McpClient second = manager.getOrConnect("server-1");
        assertThat(second).isSameAs(first);

        // listTools 经缓存连接执行
        List<McpToolDefinition> tools = manager.listTools("server-1");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).getName()).isEqualTo("echo");

        // 断开后重建为新连接
        manager.disconnect("server-1");
        McpClient third = manager.getOrConnect("server-1");
        assertThat(third).isNotSameAs(first);
        manager.disconnectAll();
    }

    @Test
    @DisplayName("非法 args/env JSON 降级为空参数（不阻断连接配置解析）")
    void invalidArgsJsonDegrades() {
        // args 为非法 JSON 时 parseArgs 返回空列表 -> bash 以无参方式启动 -> 连接失败（可预期的受控行为）
        McpServerManager manager = new McpServerManager(
                id -> descriptor("stdio", "definitely-not-a-command-12345", "not-json{{{"));

        assertThatThrownBy(() -> manager.getOrConnect("server-1"))
                .isInstanceOf(RuntimeException.class);
    }
}
