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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * fs.grep（D1-17）：工作区内按正则搜索文件内容，输出 path:lineNo: line。
 * 单文件 1MB 上限、50 个匹配上限、跳过二进制嫌疑文件（含 NUL 判定）。
 *
 * @since 1.0.0
 */
@Component
@ToolProvider(category = "CODE")
public class GrepTool implements Tool {

    private static final int MATCH_CAP = 50;
    private static final long FILE_SIZE_CAP = 1024 * 1024;

    private final LocalFileService fileService;
    private final ObjectMapper objectMapper;

    public GrepTool(LocalFileService fileService, ObjectMapper objectMapper) {
        this.fileService = fileService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("fs.grep")
                .description("在当前工作区按正则表达式搜索文件内容，返回 path:lineNo: line 清单（上限 50 条）。"
                        + "glob 可选（如 *.java）缩小文件范围。")
                .parameters("""
                        {
                          "type": "object",
                          "properties": {
                            "pattern": {"type": "string", "description": "正则表达式"},
                            "glob": {"type": "string", "description": "文件名过滤（可选，如 *.java）"}
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
            String glob = args.path("glob").asText(null);
            if (pattern == null || pattern.isBlank()) {
                return ToolResult.failure("缺少必填参数 pattern", System.currentTimeMillis() - start);
            }
            Pattern regex = Pattern.compile(pattern);
            PathMatcherLike filter = glob == null || glob.isBlank() ? null
                    : globLike(glob);
            Path root = fileService.rootFor(context.getSessionId());
            List<String> hits = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)
                        .filter(p -> !GlobTool.skipped(p))
                        .filter(p -> filter == null || filter.matches(root.relativize(p).toString()))
                        .filter(p -> sizeOk(p))::iterator) {
                    if (hits.size() >= MATCH_CAP) break;
                    searchFile(root, p, regex, hits);
                }
            }
            String output = hits.isEmpty() ? "（无匹配）"
                    : String.join("\n", hits) + (hits.size() >= MATCH_CAP ? "\n…（已达 50 条上限）" : "");
            return ToolResult.success(output, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolResult.failure("搜索失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private interface PathMatcherLike {
        boolean matches(String relativePath);
    }

    private static PathMatcherLike globLike(String glob) {
        var matcher = FileSystemsHolder.FS.getPathMatcher("glob:" + glob);
        return rel -> matcher.matches(Path.of(rel));
    }

    private static boolean sizeOk(Path p) {
        try {
            return Files.size(p) <= FILE_SIZE_CAP;
        } catch (IOException e) {
            return false;
        }
    }

    private static void searchFile(Path root, Path p, Pattern regex, List<String> hits) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            // 二进制嫌疑（含 NUL）跳过
            for (byte b : bytes) {
                if (b == 0) return;
            }
            String content = new String(bytes);
            String rel = root.relativize(p).toString().replace('\\', '/');
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length && hits.size() < MATCH_CAP; i++) {
                if (regex.matcher(lines[i]).find()) {
                    String line = lines[i];
                    hits.add(rel + ":" + (i + 1) + ": "
                            + line.substring(0, Math.min(line.length(), 200)));
                }
            }
        } catch (IOException ignored) {
            // 读失败跳过
        }
    }

    /** 延迟持有 FileSystem（避免静态初始化顺序问题） */
    private static final class FileSystemsHolder {
        static final java.nio.file.FileSystem FS = java.nio.file.FileSystems.getDefault();
    }
}
