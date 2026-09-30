package com.gewu.desktop.adapter.tools;

import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.tool.Tool;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolProvider;
import com.gewu.agent.engine.tool.ToolResult;
import com.gewu.desktop.adapter.LocalFileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * fs.glob（D1-17）：工作区内按 glob 模式匹配文件（如 **\/*.java）。
 * 自动跳过 .git/node_modules/target/dist/build 目录；上限 200 条。
 *
 * @since 1.0.0
 */
@Component
@ToolProvider(category = "CODE")
public class GlobTool implements Tool {

    private static final int RESULT_CAP = 200;
    private static final List<String> SKIP_DIRS = List.of(".git", "node_modules", "target", "dist", "build", ".next");

    private final LocalFileService fileService;
    private final ObjectMapper objectMapper;

    public GlobTool(LocalFileService fileService, ObjectMapper objectMapper) {
        this.fileService = fileService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("fs.glob")
                .description("在当前工作区按 glob 模式匹配文件路径（如 **/*.java、src/**/*.ts）。返回相对路径清单。")
                .parameters("""
                        {
                          "type": "object",
                          "properties": {
                            "pattern": {"type": "string", "description": "glob 模式，如 **/*.java"}
                          },
                          "required": ["pattern"]
                        }""")
                .build();
    }

    @Override
    public ToolResult invoke(String arguments, ToolContext context) {
        long start = System.currentTimeMillis();
        try {
            JsonNode args = objectMapper.readTree(arguments == null ? "{}" : arguments);
            String pattern = args.path("pattern").asText(null);
            if (pattern == null || pattern.isBlank()) {
                return ToolResult.failure("缺少必填参数 pattern", System.currentTimeMillis() - start);
            }
            Path root = fileService.rootFor(context.getSessionId());
            if (pattern.startsWith("/")) {
                return ToolResult.failure("pattern 必须为工作区内相对模式", System.currentTimeMillis() - start);
            }
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<String> hits = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> !skipped(p))
                        .forEach(p -> {
                            if (hits.size() >= RESULT_CAP) return;
                            String rel = root.relativize(p).toString().replace('\\', '/');
                            if (matcher.matches(Path.of(rel))) hits.add(rel);
                        });
            }
            String output = hits.isEmpty() ? "（无匹配文件）"
                    : String.join("\n", hits) + (hits.size() >= RESULT_CAP ? "\n…（已达 200 条上限）" : "");
            return ToolResult.success(output, System.currentTimeMillis() - start);
        } catch (IOException e) {
            return ToolResult.failure("扫描失败: " + e.getMessage(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.failure("参数解析失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    static boolean skipped(Path p) {
        for (Path part : p) {
            if (SKIP_DIRS.contains(part.toString())) return true;
        }
        return false;
    }
}
