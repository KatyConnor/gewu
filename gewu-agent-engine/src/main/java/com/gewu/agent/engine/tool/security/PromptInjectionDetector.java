package com.gewu.agent.engine.tool.security;

import com.gewu.agent.engine.AgentEngineException;
import com.gewu.agent.engine.spi.ToolConfig;
import com.gewu.agent.engine.tool.ToolContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 提示注入检测器 - 安全纵深五层之输入安全层。
 * <p>检测用户输入中的提示注入攻击模式：
 * <ul>
 *   <li>角色劫持："ignore previous" / "disregard above" / "you are now"</li>
 *   <li>指令注入："system:" / "<|im_start|>" / "[INST]"</li>
 *   <li>越权指令："execute" / "delete all" / "drop table"</li>
 * </ul>
 * 实现 {@link SecurityCheck} 接口，作为 SecurityChain 插件。
 *
 * @since 1.0.0
 */
@Slf4j
public class PromptInjectionDetector implements SecurityCheck {

    /** 高风险提示注入模式 */
    private static final List<Pattern> HIGH_RISK_PATTERNS = List.of(
            Pattern.compile("ignore\\s+(previous|above|all)\\s+(instructions?|prompts?|rules?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(previous|above|all)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+(a|an)\\s+\\w+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(everything|all|previous)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("new\\s+instructions?\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<\\|im_start\\|>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\[INST\\]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("system\\s*:\\s*you\\s+are", Pattern.CASE_INSENSITIVE)
    );

    /** 中风险模式 */
    private static final List<Pattern> MEDIUM_RISK_PATTERNS = List.of(
            Pattern.compile("(drop|delete|truncate)\\s+table", Pattern.CASE_INSENSITIVE),
            Pattern.compile("rm\\s+-rf\\s+/", Pattern.CASE_INSENSITIVE),
            Pattern.compile("execute\\s+(shell|cmd|command)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(sudo|chmod)\\s+\\d+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("eval\\s*\\(", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 工具参数路径的安全检查（SecurityCheck 责任链插件）。
     * <p>策略与主输入链路 {@link #checkInput(String)} 有意不同：工具边界从严，
     * 高/中风险模式均拦截（工具参数由 LLM 生成，合法技术讨论不会出现在此处，
     * 误伤概率低）；主输入链路中风险仅告警放行，避免误伤用户正常提问。
     */
    @Override
    public void check(String toolName, String arguments, ToolContext context, ToolConfig config) {
        if (arguments == null || arguments.isBlank()) return;

        for (Pattern p : HIGH_RISK_PATTERNS) {
            if (p.matcher(arguments).find()) {
                log.warn("PromptInjectionDetector: 检测到高风险提示注入! tool={}, pattern={}", toolName, p.pattern());
                throw AgentEngineException.of("PROMPT_INJECTION_DETECTED",
                        "检测到提示注入攻击: " + p.pattern());
            }
        }

        for (Pattern p : MEDIUM_RISK_PATTERNS) {
            if (p.matcher(arguments).find()) {
                log.warn("PromptInjectionDetector: 检测到中风险模式! tool={}, pattern={}", toolName, p.pattern());
                throw AgentEngineException.of("SUSPICIOUS_TOOL_ARGS",
                        "检测到可疑指令模式: " + p.pattern());
            }
        }
    }

    /**
     * 检查用户原始输入（聊天主链路入口）。
     * <p>高风险模式（角色劫持 / 指令注入）直接拦截；中风险模式（SQL/Shell 关键词等
     * 合法技术讨论常见词）仅告警放行，避免误伤正常提问。
     */
    public void checkInput(String input) {
        if (input == null || input.isBlank()) return;

        for (Pattern p : HIGH_RISK_PATTERNS) {
            if (p.matcher(input).find()) {
                log.warn("PromptInjectionDetector: 用户输入命中高风险注入模式: {}", p.pattern());
                throw AgentEngineException.of("PROMPT_INJECTION_DETECTED",
                        "检测到提示注入攻击，输入已拦截");
            }
        }

        for (Pattern p : MEDIUM_RISK_PATTERNS) {
            if (p.matcher(input).find()) {
                log.warn("PromptInjectionDetector: 用户输入命中中风险模式（放行并告警）: {}", p.pattern());
            }
        }
    }

    /**
     * 检查输出安全（非 SecurityCheck 接口方法，供 ToolExecutor 调用）。
     */
    public String checkOutput(String output) {
        return output; // 输出安全由 OutputSanitizer 负责
    }
}