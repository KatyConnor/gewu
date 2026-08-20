package com.gewu.agent.engine.tool.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.ToolContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 代码扫描安全检查 - 将 {@link CodeScanner} SPI 适配为 {@link SecurityCheck} 插件
 * （安全纵深五层之工具安全层）。
 * <p>当工具类型为 code_execute 时，从参数 JSON 中提取 code/language 执行危险操作扫描，
 * 使 ToolExecutor 无需独立持有 CodeScanner，统一经 SecurityChain 调度。
 *
 * @since 1.0.0
 */
@Slf4j
@RequiredArgsConstructor
public class CodeScannerCheck implements SecurityCheck {

    private final CodeScanner delegate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void check(String toolName, String arguments, ToolContext context, ToolConfig config) {
        if (config == null || !"code_execute".equals(config.getToolType())) {
            return;
        }
        if (arguments == null || arguments.isBlank()) {
            return;
        }

        String language;
        String code;
        try {
            JsonNode node = objectMapper.readTree(arguments);
            language = node.has("language") ? node.get("language").asText() : "python";
            code = node.has("code") ? node.get("code").asText()
                    : node.has("command") ? node.get("command").asText() : arguments;
        } catch (Exception e) {
            language = "shell";
            code = arguments;
        }

        delegate.scan(code, language);
    }
}
