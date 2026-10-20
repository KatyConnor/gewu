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
 * 调度入口（AGENTS.md/GEWU.md 跨层裁决，与系统提示词注入共用 EntryFileResolver）
 * → rules/*.md → .gewu/skills/*.md（与格物平台仓库约定同源），
 * 供模型了解工作区协作规范。总量 16KB 上限（字符口径）。
 *
 * @since 1.0.0
 */
@Component
@ToolProvider(category = "DOC")
public class ProjectContextTool implements Tool {

    private static final int TOTAL_CAP = 16_000;

    private final LocalFileService fileService;
    private final com.gewu.desktop.entry.EntryFileResolver entryFileResolver;

    public ProjectContextTool(LocalFileService fileService,
                              com.gewu.desktop.entry.EntryFileResolver entryFileResolver) {
        this.fileService = fileService;
        this.entryFileResolver = entryFileResolver;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("project.context")
                .description("读取当前工作区的项目协作上下文：调度入口（AGENTS.md/GEWU.md）、rules/*.md（编码规则）、"
                        + ".gewu/skills/*.md（本地技能）。写代码/改文件前建议先调用以了解项目约定。")
                .parameters("{}")
                .build();
    }

    @Override
    public ToolResult invoke(String arguments, ToolContext context) {
        long start = System.currentTimeMillis();
        try {
            Path root = fileService.rootFor(context);
            StringBuilder sb = new StringBuilder();
            int budget = TOTAL_CAP;
            // 1) 调度入口（GEWU.md/AGENTS.md 跨层裁决，与系统提示词注入同源——杜绝双源分叉）
            try {
                var sel = entryFileResolver.resolveSelection(root.toString());
                if (sel.getContent() != null && !sel.getContent().isEmpty()) {
                    String header = "## 调度入口（" + sel.getSource() + "）";
                    String content = sel.getContent();
                    if (content.length() > budget) {
                        content = content.substring(0, Math.max(0, budget)) + "\n…（截断）";
                    }
                    sb.append("\n").append(header).append("\n").append(content).append("\n");
                    budget -= content.length();
                }
            } catch (Exception ignored) {
                // 入口解析失败降级为无该段，rules/skills 照常装载
            }
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
            // 3) 技能:全局 ~/.gewu/skills/*.md(安装目录,用户指定)+ 工作区 .gewu/skills/*.md(项目级,兼容)
            Path globalSkills = Path.of(System.getProperty("user.home"), ".gewu", "skills");
            if (Files.isDirectory(globalSkills)) {
                try (Stream<Path> list = Files.list(globalSkills)) {
                    for (Path p : (Iterable<Path>) list.filter(f -> f.toString().endsWith(".md")).sorted()::iterator) {
                        if (budget <= 0) break;
                        budget = appendFile(sb, p, "## skills/" + p.getFileName(), budget);
                    }
                }
            }
            Path wsSkills = root.resolve(".gewu").resolve("skills");
            if (Files.isDirectory(wsSkills)) {
                try (Stream<Path> list = Files.list(wsSkills)) {
                    for (Path p : (Iterable<Path>) list.filter(f -> f.toString().endsWith(".md")).sorted()::iterator) {
                        if (budget <= 0) break;
                        budget = appendFile(sb, p, "## .gewu/skills/" + p.getFileName(), budget);
                    }
                }
            }
            if (sb.isEmpty()) {
                return ToolResult.success("（工作区无调度入口 / rules / .gewu/skills 上下文）",
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
