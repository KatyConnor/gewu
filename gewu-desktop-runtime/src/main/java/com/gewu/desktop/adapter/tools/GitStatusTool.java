package com.gewu.desktop.adapter.tools;

import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.tool.Tool;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolProvider;
import com.gewu.agent.engine.tool.ToolResult;
import com.gewu.desktop.adapter.LocalFileService;
import org.springframework.stereotype.Component;

/** git Status（D1-18，只读）：工作区 Git 状态查询。 */
@Component
@ToolProvider(category = "GIT")
public class GitStatusTool implements Tool {

    private final LocalFileService fileService;

    public GitStatusTool(LocalFileService fileService) {
        this.fileService = fileService;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("git.status")
                .description("查看工作区 Git 状态（分支与变更文件清单，--short --branch 格式）")
                .parameters("{}")
                .build();
    }

    @Override
    public ToolResult invoke(String arguments, ToolContext context) {
        long start = System.currentTimeMillis();
        String out = GitCliSupport.run(fileService, context, "status --short --branch");
        return ToolResult.success(out, System.currentTimeMillis() - start);
    }
}
