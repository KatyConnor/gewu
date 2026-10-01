package com.gewu.desktop.adapter.tools;

import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.spi.ExecResult;
import com.gewu.desktop.adapter.LocalDockerSandboxExecutor;
import com.gewu.agent.engine.tool.Tool;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolProvider;
import com.gewu.agent.engine.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * code_execute（D2-3）：模型驱动的隔离代码执行——经 SandboxExecutor SPI
 * （桌面=本地 Docker；协同=云端回退由上层路由）。写入 /workspace 的文件
 * 与会话文件工具共享同一持久视图（卷挂载）。
 *
 * @since 1.0.0
 */
@Component
@ToolProvider(category = "CODE")
public class CodeExecuteTool implements Tool {

    private final LocalDockerSandboxExecutor sandboxExecutor;
    private final ObjectMapper objectMapper;

    public CodeExecuteTool(LocalDockerSandboxExecutor sandboxExecutor, ObjectMapper objectMapper) {
        this.sandboxExecutor = sandboxExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("code_execute")
                .description("在隔离沙箱中执行代码（shell/python/node），工作区挂载到 /workspace——"
                        + "可读取/写入工作区文件后验证运行结果。shell 用 sh；python/node 视镜像而定。")
                .parameters("""
                        {
                          "type": "object",
                          "properties": {
                            "language": {"type": "string", "description": "语言：shell/python/node（默认 shell）"},
                            "code": {"type": "string", "description": "代码内容"},
                            "timeoutSeconds": {"type": "integer", "description": "超时秒数（默认 60，上限 300）"}
                          },
                          "required": ["code"]
                        }""")
                .build();
    }

    @Override
    public ToolResult invoke(String arguments, ToolContext context) {
        long start = System.currentTimeMillis();
        try {
            JsonNode args = objectMapper.readTree(arguments == null ? "{}" : arguments);
            String language = args.path("language").asText("shell");
            String code = args.path("code").asText(null);
            int timeout = args.path("timeoutSeconds").asInt(60);
            if (code == null || code.isBlank()) {
                return ToolResult.failure("缺少必填参数 code", System.currentTimeMillis() - start);
            }
            ExecResult r = sandboxExecutor.executeForSession(
                    context != null ? context.getSessionId() : null, language, code, timeout);
            String out = (r.getStdout() == null ? "" : r.getStdout())
                    + (r.getStderr() == null || r.getStderr().isBlank() ? "" : "\n[stderr]\n" + r.getStderr());
            if (r.isSuccess()) {
                return ToolResult.success("exit=0\n" + out, System.currentTimeMillis() - start);
            }
            return ToolResult.failure("exit=" + r.getExitCode() + "\n" + out,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.failure("执行异常: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }
}
