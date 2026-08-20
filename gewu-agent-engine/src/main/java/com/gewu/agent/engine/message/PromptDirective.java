package com.gewu.agent.engine.message;

/**
 * 提示词指令工具 - 提供 Agent 模式与思维模式的 system prompt 指令及温度映射。
 *
 * @since 1.0.0
 */
public final class PromptDirective {

    private PromptDirective() {
    }

    /**
     * Agent 模式指令。
     * <ul>
     *   <li>assistant - 通用助手（无额外指令）</li>
     *   <li>expert - 专家模式，严谨准确</li>
     *   <li>creative - 创意模式，发散思维</li>
     *   <li>precise - 精确模式，严格遵循</li>
     * </ul>
     */
    public static String buildAgentModeDirective(String agentMode) {
        if (agentMode == null || agentMode.isBlank()) {
            return null;
        }
        return switch (agentMode) {
            case "expert" -> "【专家模式】请以严谨、专业的态度回答问题，确保信息准确无误，引用权威来源。避免不确定的推测，如不确定请明确说明。";
            case "creative" -> "【创意模式】请以富有创造力和想象力的方式回答问题，鼓励发散思维，提供新颖独特的视角和方案。";
            case "precise" -> "【精确模式】请严格遵循用户指令，输出简洁精确的结果，不添加多余的解释或修饰。";
            default -> null;
        };
    }

    /**
     * 思维模式指令。
     * <ul>
     *   <li>chain-of-thought - 链式推理</li>
     *   <li>tree-of-thought - 树状推理</li>
     *   <li>react - ReAct 模式</li>
     *   <li>step-by-step - 逐步分析</li>
     *   <li>socratic - 苏格拉底式</li>
     * </ul>
     */
    public static String buildThinkingStyleDirective(String thinkingStyle) {
        if (thinkingStyle == null || thinkingStyle.isBlank()) {
            return null;
        }
        return switch (thinkingStyle) {
            case "chain-of-thought" -> "【链式推理】请逐步展示你的推理过程，先分析问题，再逐步推导，最后给出结论。用「思考：」标记推理步骤。";
            case "tree-of-thought" -> "【树状推理】请从多个角度分析问题，探索不同的解决路径，比较各路径的优劣，选择最优方案。用「分支N：」标记不同思路。";
            case "react" -> "【ReAct 模式】请使用「思考->行动->观察」循环解决问题。用「思考：」标记推理，用「行动：」标记操作，用「观察：」标记结果。";
            case "step-by-step" -> "【逐步分析】请将问题分解为清晰的步骤，逐步解决，每步标注序号。";
            case "socratic" -> "【苏格拉底式】请通过提问引导用户思考，不直接给出答案，而是用层层递进的问题帮助用户自行发现结论。";
            default -> null;
        };
    }

    /** 根据 agentMode 获取 temperature */
    public static double getTemperatureByMode(String agentMode) {
        if (agentMode == null) {
            return 0.7;
        }
        return switch (agentMode) {
            case "expert" -> 0.3;
            case "creative" -> 0.9;
            case "precise" -> 0.1;
            default -> 0.7;
        };
    }
}
