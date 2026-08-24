package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.orchestration.model.GraphNode;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.ToolContext;
import com.gewu.agent.engine.tool.ToolExecutor;
import com.gewu.agent.engine.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图节点执行器 - TOOL 类型节点的执行支撑。
 * <p>节点 config 约定：
 * <ul>
 *   <li>{@code toolName} - 工具名（必填）</li>
 *   <li>{@code arguments} - 参数 JSON 模板，支持 {@code ${变量名}} 占位符（从图上下文变量渲染）</li>
 *   <li>{@code outputVar} - 产出写入的变量名（缺省为节点 ID）</li>
 * </ul>
 * 执行经 {@link ToolExecutor} 五段安全管线（Schema/注入/权限/截断/审计），
 * 工具配置为 null 时按代码工具（{@code ToolRegistry} 注册）解析。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class GraphNodeExecutor {

    /** 参数模板占位符：${varName} */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([a-zA-Z0-9_.]+)}");

    private final ToolExecutor toolExecutor;

    /**
     * 执行 TOOL 节点：渲染参数模板 -> ToolExecutor -> 产出写入上下文变量。
     *
     * @return 工具输出文本
     */
    public String executeToolNode(GraphNode node, OrchestrationContext ctx) {
        Map<String, Object> config = node.getConfig();
        if (config == null || config.get("toolName") == null) {
            throw AgentEngineException.of("NODE_CONFIG_INVALID",
                    "TOOL 节点缺少 toolName 配置: " + node.getNodeId());
        }
        String toolName = String.valueOf(config.get("toolName"));
        String argumentsTemplate = config.get("arguments") != null
                ? String.valueOf(config.get("arguments")) : "{}";
        String arguments = renderTemplate(argumentsTemplate, ctx);

        ToolContext toolContext = ToolContext.builder()
                .userId(ctx.getUserId())
                .sessionId(ctx.getSessionId())
                .timeout(30)
                .build();
        // 编排节点级工具暂不绑定 Agent 级 ToolConfig（工具配置由 ToolRegistry 代码注册优先）
        ToolResult result = toolExecutor.execute(toolName, arguments, toolContext, null);
        if (!result.isSuccess()) {
            throw AgentEngineException.of("NODE_TOOL_FAILED",
                    "TOOL 节点执行失败: " + node.getNodeId() + " - " + result.getError());
        }
        String output = result.getOutput() != null ? result.getOutput() : "";
        String outputVar = config.get("outputVar") != null
                ? String.valueOf(config.get("outputVar")) : node.getNodeId();
        ctx.putVariable(outputVar, output);
        log.info("TOOL 节点完成: nodeId={}, tool={}, outputVar={}, outputLen={}",
                node.getNodeId(), toolName, outputVar, output.length());
        return output;
    }

    /**
     * 渲染参数模板：将 {@code ${varName}} 替换为上下文变量值（未知变量替换为空串并告警）。
     */
    String renderTemplate(String template, OrchestrationContext ctx) {
        if (template == null || !template.contains("${")) {
            return template;
        }
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = ctx.getVariable(varName);
            if (value == null) {
                log.warn("模板变量未定义，替换为空串: {} (节点参数渲染)", varName);
            }
            String replacement = value != null ? String.valueOf(value) : "";
            // Matcher.quoteReplacement 防替换串中的 $/\ 破坏渲染
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
