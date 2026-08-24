package com.gewu.agent.engine.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StdioMcpClient} JSON-RPC 协议测试。
 * <p>用 bash 脚本模拟最小 MCP stdio 服务器（按 method 响应固定 JSON-RPC），
 * 验证 initialize 握手、tools/list、tools/call 与错误处理。
 */
@DisplayName("Stdio MCP 客户端")
class StdioMcpClientTest {

    /**
     * 模拟 MCP 服务器的 bash 脚本：按 method 响应 JSON-RPC。
     * 注意：bash case 模式中双引号是语法引号而非字面量，故用无引号子串匹配；
     * notifications/initialized 分支置前且无响应（通知不回包）。
     */
    private static final String MCP_SERVER_SCRIPT =
            "while read line; do case \"$line\" in "
                    + "*notifications/initialized*) ;; "
                    + "*badtool*) echo '{\"jsonrpc\":\"2.0\",\"id\":9,\"error\":{\"code\":-32000,\"message\":\"tool not found\"}}';; "
                    + "*initialize*) echo '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\",\"serverInfo\":{\"name\":\"mock\"}}}';; "
                    + "*tools/list*) echo '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"回声工具\",\"inputSchema\":{\"type\":\"object\"}}]}}';; "
                    + "*tools/call*) echo '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"mcp-ok\"}],\"isError\":false}}';; "
                    + "esac; done";

    private StdioMcpClient connectedClient() throws Exception {
        StdioMcpClient client = new StdioMcpClient("bash",
                List.of("-c", MCP_SERVER_SCRIPT), Map.of("MOCK_ENV", "1"));
        client.connect();
        return client;
    }

    @Test
    @DisplayName("initialize 握手成功并进入已连接状态")
    void connectInitializes() throws Exception {
        StdioMcpClient client = connectedClient();
        try {
            assertThat(client.isConnected()).isTrue();
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("tools/list 解析工具清单")
    void listToolsParsesDefinitions() throws Exception {
        StdioMcpClient client = connectedClient();
        try {
            List<McpToolDefinition> tools = client.listTools();

            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).getName()).isEqualTo("echo");
            assertThat(tools.get(0).getDescription()).isEqualTo("回声工具");
            assertThat(tools.get(0).getInputSchema()).contains("\"object\"");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("tools/call 成功：拼接 content 中的 text 输出")
    void callToolSuccess() throws Exception {
        StdioMcpClient client = connectedClient();
        try {
            McpToolResult result = client.callTool("echo", "{\"text\":\"hi\"}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).isEqualTo("mcp-ok");
            assertThat(result.getError()).isNull();
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("tools/call 错误响应：结果标记失败并携带错误信息")
    void callToolErrorResponse() throws Exception {
        StdioMcpClient client = connectedClient();
        try {
            McpToolResult result = client.callTool("badtool", "{}");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("tool not found");
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("close 之后连接状态失效")
    void closeInvalidatesConnection() throws Exception {
        StdioMcpClient client = connectedClient();
        assertThat(client.isConnected()).isTrue();

        client.close();

        assertThat(client.isConnected()).isFalse();
    }

    @Test
    @DisplayName("服务器立即退出：连接抛出通信异常")
    void serverImmediateExitFails() {
        StdioMcpClient client = new StdioMcpClient("bash", List.of("-c", "exit 0"), null);

        assertThatThrownBy(client::connect)
                .isInstanceOf(Exception.class)
                .hasMessageContaining("MCP server closed connection");
    }
}
