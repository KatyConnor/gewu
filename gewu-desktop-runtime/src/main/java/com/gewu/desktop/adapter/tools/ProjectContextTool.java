package com.gewu.desktop.adapter.tools;

import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.tool.Tool;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolProvider;
import com.gewu.agent.engine.tool.ToolResult;
import com.gewu.desktop.adapter.LocalFileService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * project.context（D1-18）：装载工作区项目上下文——
 * AGENTS.md → rules/*.md → .gewu/skills/*.md（与格物平台仓库约定同源），
 * 供模型了解工作区协作规范。总量 16KB 上限。
 *
 * @since 1.0.0
 */
@Component
@ToolProvider(category = "DOC")
public class ProjectContextTool implements Tool {

    private static final int TOTAL_CAP = 16_000;

    private final LocalFileService fileService;

    public ProjectContextTool(LocalFileService fileService) {
        this.fileService = fileService;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("project.context")
                .description("读取当前工作区的项目协作上下文：AGENTS.md（Agent 规范）、rules/*.md（编码规则）、"
                        + ".gewu/skills/*.md（本地技能）。写代码/改文件前建议先调用以了解项目约定。")
                .parameters("{}")
                .build();
    }

    @Override
    public ToolResult invoke(String arguments, ToolContext context) {
        long start = System.currentTimeMillis();
        try {
            Path root = fileService.rootFor(context.getSessionId());
            StringBuilder sb = new StringBuilder();
            int budget = TOTAL_CAP;
            // 1) AGENTS.md
            budget = appendFile(sb, root.resolve("AGENTS.md"), "## AGENTS.md", budget);
            // 2) rules/*.md
            Path rules = root.resolve("rules");
            if (Files.isDirectory(rules)) {
                try (Stream<Path> list = Files.list(rules)) {
                    for (Path p : (Iterable<Path>) list.filter(f -> f.toString().endsWith(".md")).sorted()::iterator) {
                        if (budget <= 0) break;
                        budget = appendFile(sb, p, "## rules/" + p.getFileName(), budget);
                    }
                }
            }
            // 3) .gewu/skills/*.md
            Path skills = root.resolve(".gewu").resolve("skills");
            if (Files.isDirectory(skills)) {
                try (Stream<Path> list = Files.list(skills)) {
                    for (Path p : (Iterable<Path>) list.filter(f -> f.toString().endsWith(".md")).sorted()::iterator) {
                        if (budget <= 0) break;
                        budget = appendFile(sb, p, "## .gewu/skills/" + p.getFileName(), budget);
                    }
                }
            }
            if (sb.isEmpty()) {
                return ToolResult.success("（工作区无 AGENTS.md / rules / .gewu/skills 上下文）",
                        System.currentTimeMillis() - start);
            }
            return ToolResult.success(sb.toString(), System.currentTimeMillis() - start);
        } catch (IOException e) {
            return ToolResult.failure("上下文装载失败: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    private int appendFile(StringBuilder sb, Path file, String header, int budget) throws IOException {
        if (!Files.isRegularFile(file) || budget <= 0) {
            return budget;
        }
        String content = Files.readString(file);
        if (content.length() > budget) {
            content = content.substring(0, budget) + "\n…（截断）";
        }
        sb.append("\n").append(header).append("\n").append(content).append("\n");
        return budget - content.length();
    }
}
