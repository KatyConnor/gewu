package com.gewu.desktop.adapter.tools;

import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.tool.Tool;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolProvider;
import com.gewu.agent.engine.tool.ToolResult;
import com.gewu.desktop.adapter.LocalFileService;
import com.gewu.desktop.security.DesktopCommandValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * shell.exec（D1-16）：工作区内命令执行。
 * 防线：DesktopCommandValidator 黑名单 → 三级权限模式（ToolExecutor 管线）→ 超时/进程树清理 → 输出截断。
 *
 * @since 1.0.0
 */
@Component
@ToolProvider(category = "CODE")
public class ShellExecTool implements Tool {

    private static final long TIMEOUT_CAP_SECONDS = 300;
    private static final int OUTPUT_CAP = 10_000;

    private final LocalFileService fileService;
    private final ObjectMapper objectMapper;

    public ShellExecTool(LocalFileService fileService, ObjectMapper objectMapper) {
        this.fileService = fileService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("shell.exec")
                .description("在当前工作区执行 shell 命令并返回输出（构建/测试/脚本等）。"
                        + "危险命令会被拒绝；Windows 用 cmd /c，其余用 sh -c。"
                        + "cwd 为工作区内相对目录（缺省工作区根），timeoutSeconds 上限 300。")
                .parameters("""
                        {
                          "type": "object",
                          "properties": {
                            "command": {"type": "string", "description": "要执行的 shell 命令"},
                            "cwd": {"type": "string", "description": "工作区内相对目录（可选）"},
                            "timeoutSeconds": {"type": "integer", "description": "超时秒数（可选，默认 30，上限 300）"}
                          },
                          "required": ["command"]
                        }""")
                .build();
    }

    @Override
    public ToolResult invoke(String arguments, ToolContext context) {
        long start = System.currentTimeMillis();
        try {
            JsonNode args = objectMapper.readTree(arguments == null ? "{}" : arguments);
            String command = args.path("command").asText(null);
            String cwd = args.path("cwd").asText(null);
            int timeout = Math.min(args.path("timeoutSeconds").asInt(30), (int) TIMEOUT_CAP_SECONDS);
            if (command == null || command.isBlank()) {
                return ToolResult.failure("缺少必填参数 command", System.currentTimeMillis() - start);
            }
            String deny = DesktopCommandValidator.validate(command);
            if (deny != null) {
                return ToolResult.failure(deny, System.currentTimeMillis() - start);
            }
            Path root = fileService.rootFor(context);
            Path cwdPath = cwd == null || cwd.isBlank() ? root
                    : com.gewu.desktop.security.PathGuard.secureResolve(root, cwd);
            if (!Files.isDirectory(cwdPath)) {
                return ToolResult.failure("工作目录不存在: " + cwd, System.currentTimeMillis() - start);
            }
            ProcessBuilder pb = new ProcessBuilder(isWindows() ? "cmd" : "sh",
                    isWindows() ? "/c" : "-c", command);
            pb.directory(cwdPath.toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (var is = proc.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0 && out.length() < OUTPUT_CAP * 2) {
                        out.append(new String(buf, 0, n));
                    }
                } catch (IOException ignored) {
                    // 进程结束/超时导致的流关闭
                }
            });
            reader.start();
            boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
                return ToolResult.failure("命令执行超时（" + timeout + "s），已终止。已产生的输出：\n"
                        + truncate(out.toString()), System.currentTimeMillis() - start);
            }
            reader.join(2000);
            String output = truncate(out.toString());
            return ToolResult.success("exit=" + proc.exitValue() + "\n" + output,
                    System.currentTimeMillis() - start);
        } catch (SecurityException e) {
            return ToolResult.failure("被拒绝: " + e.getMessage(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.failure("执行异常: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String truncate(String s) {
        return s.length() > OUTPUT_CAP ? s.substring(0, OUTPUT_CAP) + "\n…（输出已截断）" : s;
    }
}
