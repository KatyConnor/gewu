package com.gewu.agent.engine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * 内置工具执行器（AE-P2-1 三期切片）：内置五件套的执行逻辑，与 {@link BuiltinToolCatalog}
 *（目录管"是什么"）配对——本类管"怎么跑"。从 ReactAgentExecutor 外移（三期步1起分步迁移）。
 * <p><b>语义不变量</b>：内置工具由执行器直接分发执行，<b>不经过</b> ToolExecutor 五段
 * 安全管线——其操作对象是会话工作空间、用户交互与子代理派生等引擎内受控能力。
 * <p>协作方式：持 owner（ReactAgentExecutor）引用以复用共享 helper（truncate 等，
 * 包级可见），分步迁移期保持行为逐字节一致。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-14
 */
@Slf4j
class BuiltinToolExecutor {

    private final ReactAgentExecutor owner;
    private final com.gewu.agent.engine.tool.FileWorkspaceSpi fileWorkspace;
    private final ObjectMapper objectMapper;

    BuiltinToolExecutor(ReactAgentExecutor owner,
                        com.gewu.agent.engine.tool.FileWorkspaceSpi fileWorkspace,
                        ObjectMapper objectMapper) {
        this.owner = owner;
        this.fileWorkspace = fileWorkspace;
        this.objectMapper = objectMapper;
    }

    /** 文件工具执行（S9 F3）：read/write/edit/list，经 FileWorkspaceSpi 路由到会话工作空间 */
    ToolResult executeFileTool(com.gewu.agent.engine.llm.model.ToolCall tc, ToolContext toolContext) {
        if (fileWorkspace == null || !fileWorkspace.available()) {
            return ToolResult.builder().success(false).error("文件工具未配置").build();
        }
        String name = tc.getName();
        String args = tc.getArguments() != null ? tc.getArguments() : "{}";
        try {
            JsonNode root = objectMapper.readTree(args);
            String path = root.path("path").asText("");
            // list_dir 的 path 可省略（默认工作空间根，与 LIST_DIR_SCHEMA required:[] 一致）
            if (path.isBlank() && !BuiltinToolCatalog.LIST_DIR_TOOL.equals(name)) {
                return ToolResult.builder().success(false).error("缺少 path 参数").build();
            }
            switch (name) {
                case BuiltinToolCatalog.READ_FILE_TOOL -> {
                    String content = fileWorkspace.readFile(toolContext, path);
                    if (content == null) {
                        return ToolResult.builder().success(false).error(
                                "文件不存在: " + path
                                        + "（path 应为相对当前工作空间根目录的路径，不要以 / 开头或加 workspace/ 前缀；"
                                        + "@global/ 前缀可只读访问全局资源；可用 list_dir 查看根目录内容后重试）").build();
                    }
                    // 内容截断保护（与外发工具同款输出上限策略，默认 10KB）
                    return ToolResult.builder().success(true)
                            .output(ReactAgentExecutor.appendNestedEntry(
                                    owner.truncate(content, 10 * 1024), toolContext, path, fileWorkspace))
                            .build();
                }
                case BuiltinToolCatalog.WRITE_FILE_TOOL -> {
                    String content = root.path("content").asText("");
                    fileWorkspace.writeFile(toolContext, path, content);
                    ReactAgentExecutor.invalidateNestedProbeCache(toolContext, path);
                    return ToolResult.builder().success(true)
                            .output(ReactAgentExecutor.appendNestedEntry(
                                    "已写入 " + path + "（" + content.length() + " 字符）", toolContext, path, fileWorkspace))
                            .build();
                }
                case BuiltinToolCatalog.EDIT_FILE_TOOL -> {
                    String oldText = root.path("old_text").asText("");
                    String newText = root.path("new_text").asText("");
                    if (oldText.isEmpty()) {
                        return ToolResult.builder().success(false).error("缺少 old_text 参数").build();
                    }
                    String current = fileWorkspace.readFile(toolContext, path);
                    if (current == null) {
                        return ToolResult.builder().success(false).error(
                                "文件不存在: " + path
                                        + "（path 应为相对当前工作空间根目录的路径，不要以 / 开头或加 workspace/ 前缀；"
                                        + "可用 list_dir 查看根目录内容后重试）").build();
                    }
                    int idx = current.indexOf(oldText);
                    if (idx < 0) {
                        return ToolResult.builder().success(false)
                                .error("未找到要替换的文本（old_text 不匹配）: " + path).build();
                    }
                    if (current.indexOf(oldText, idx + 1) >= 0) {
                        return ToolResult.builder().success(false)
                                .error("old_text 在文件中多处匹配，请提供更长的唯一上下文").build();
                    }
                    String updated = current.substring(0, idx) + newText + current.substring(idx + oldText.length());
                    fileWorkspace.writeFile(toolContext, path, updated);
                    // 起始行号与增删行数随结果下发：前端过程时间线据此渲染 zcode 风格差异块（+N -N / 红绿行号）
                    int startLine = 1;
                    for (int i = 0; i < idx; i++) {
                        if (current.charAt(i) == '\n') startLine++;
                    }
                    int[] diff = ReactAgentExecutor.diffLineCounts(oldText, newText);
                    ReactAgentExecutor.invalidateNestedProbeCache(toolContext, path);
                    return ToolResult.builder().success(true)
                            .output(ReactAgentExecutor.appendNestedEntry(
                                    "已编辑 " + path + "（第 " + startLine + " 行起，+" + diff[0] + " -" + diff[1] + "）",
                                    toolContext, path, fileWorkspace))
                            .build();
                }
                case BuiltinToolCatalog.LIST_DIR_TOOL -> {
                    java.util.List<String> entries = fileWorkspace.listDir(toolContext, path);
                    if (entries.isEmpty()) {
                        return ToolResult.builder().success(true).output("(空目录)").build();
                    }
                    return ToolResult.builder().success(true)
                            .output(owner.truncate(String.join("\n", entries), 4096)).build();
                }
                default -> {
                    return ToolResult.builder().success(false).error("未知内置工具: " + name).build();
                }
            }
        } catch (Exception e) {
            log.warn("文件工具执行失败: err={}", e.getMessage());
            // 通用文案（CR L3-SEC-020 邻域：原始异常可能含沙箱内部路径，不进模型上下文）
            return ToolResult.builder().success(false).error("文件操作失败，请重试或调整参数").build();
        }
    }
}
