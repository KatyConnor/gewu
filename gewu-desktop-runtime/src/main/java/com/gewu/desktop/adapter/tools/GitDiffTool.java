package com.gewu.desktop.adapter.tools;

import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.tool.Tool;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolProvider;
import com.gewu.agent.engine.tool.ToolResult;
import com.gewu.desktop.adapter.LocalFileService;
import org.springframework.stereotype.Component;

/** git Diff（D1-18，只读）：工作区 Git 状态查询。 */
@Component
@ToolProvider(category = "GIT")
public class GitDiffTool implements Tool {

    private final LocalFileService fileService;

    public GitDiffTool(LocalFileService fileService) {
        this.fileService = fileService;
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("git.diff")
                .description("查看工作区未暂存变更的 diff 内容（git diff）")
                .parameters("{}")
                .build();
    }

    @Override
    public ToolResult invoke(String arguments, ToolContext context) {
        long start = System.currentTimeMillis();
        String out = GitCliSupport.run(fileService, context.getSessionId(), "diff");
        return ToolResult.success(out, System.currentTimeMillis() - start);
    }
}
