package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.agent.dto.AgentExecutionRequest;
import com.gewu.application.ai.ModelConfigService;
import com.gewu.application.session.SessionContextService;
import com.gewu.domain.agent.Agent;
import com.gewu.domain.agent.AgentSkill;
import com.gewu.domain.skill.Skill;
import com.gewu.infrastructure.llm.Message;
import com.gewu.infrastructure.mapper.AgentSkillMapper;
import com.gewu.infrastructure.mapper.SkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 消息构建器 — 负责构建 LLM 请求的消息列表，包括 system prompt、历史上下文和用户消息.
 *
 * <p>职责：
 * <ul>
 *   <li>组装 system prompt（agent 原始 prompt + agentMode 指令 + thinkingStyle 指令）</li>
 *   <li>加载会话历史上下文</li>
 *   <li>根据 agentMode 设置 temperature 参数</li>
 *   <li>解析 LLM 供应商和模型</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AgentMessageBuilder {

    private static final int DEFAULT_HISTORY_LIMIT = 50;

    private final SessionContextService sessionContextService;
    private final ModelConfigService modelConfigService;
    private final AgentSkillMapper agentSkillMapper;
    private final SkillMapper skillMapper;

    /**
     * 根据 model ID 解析 LLM 提供商和模型名称。
     * 当 agentId 为 null 时，通过 model 字段从数据库查找供应商编码。
     *
     * @param agent Agent 实体（可能为 null）
     * @param model 前端传入的模型 ID（如 qwen-plus）
     * @return [providerCode, modelName] 数组
     */
    public String[] resolveProviderAndModel(Agent agent, String model) {
        if (agent != null) {
            return new String[]{agent.getModelProvider(), agent.getModelName()};
        }
        // 直接对话模式：从数据库查找供应商
        if (model != null && !model.isBlank()) {
            String providerCode = modelConfigService.getProviderCodeByModelId(model);
            if (providerCode != null) {
                return new String[]{providerCode, model};
            }
        }
        // 兜底：使用默认 qwen
        return new String[]{"qwen", "qwen-plus"};
    }

    /**
     * 构建完整的消息列表，包括 system prompt、历史上下文和用户消息。
     */
    public List<Message> buildMessages(Agent agent, AgentExecutionRequest request) {
        List<Message> messages = new ArrayList<>();

        // 构建 system prompt：agent 原始 prompt + agentMode 指令 + thinkingStyle 指令
        StringBuilder systemPromptBuilder = new StringBuilder();
        if (agent != null && agent.getSystemPrompt() != null && !agent.getSystemPrompt().isEmpty()) {
            systemPromptBuilder.append(agent.getSystemPrompt());
        }

        // 注入已挂载技能的 content（能力指令），赋予智能体领域能力
        if (agent != null && agent.getId() != null) {
            List<AgentSkill> agentSkills = agentSkillMapper.selectList(
                    new LambdaQueryWrapper<AgentSkill>()
                            .eq(AgentSkill::getAgentId, agent.getId())
                            .orderByAsc(AgentSkill::getSortOrder));
            if (!agentSkills.isEmpty()) {
                List<String> skillIds = agentSkills.stream().map(AgentSkill::getSkillId).toList();
                Map<String, Skill> skillMap = skillMapper.selectBatchIds(skillIds).stream()
                        .collect(Collectors.toMap(Skill::getId, s -> s));
                StringBuilder skillSection = new StringBuilder();
                for (AgentSkill as : agentSkills) {
                    Skill skill = skillMap.get(as.getSkillId());
                    if (skill != null && skill.getContent() != null && !skill.getContent().isBlank()) {
                        skillSection.append("\n\n## 技能：").append(skill.getSkillName())
                                .append("\n").append(skill.getContent());
                    }
                }
                if (skillSection.length() > 0) {
                    if (systemPromptBuilder.length() > 0) systemPromptBuilder.append("\n\n# 已挂载技能");
                    systemPromptBuilder.append(skillSection);
                }
            }
        }

        // 注入 Agent 模式指令
        String modeDirective = buildAgentModeDirective(request.getAgentMode());
        if (modeDirective != null) {
            if (systemPromptBuilder.length() > 0) systemPromptBuilder.append("\n\n");
            systemPromptBuilder.append(modeDirective);
        }

        // 注入思维模式指令
        String thinkingDirective = buildThinkingStyleDirective(request.getThinkingStyle());
        if (thinkingDirective != null) {
            if (systemPromptBuilder.length() > 0) systemPromptBuilder.append("\n\n");
            systemPromptBuilder.append(thinkingDirective);
        }

        if (systemPromptBuilder.length() > 0) {
            messages.add(Message.builder()
                    .role("system")
                    .content(systemPromptBuilder.toString())
                    .build());
        }

        if (request.getSessionId() != null) {
            List<Message> contextMessages = sessionContextService.buildContextMessages(
                    request.getSessionId(), DEFAULT_HISTORY_LIMIT);
            messages.addAll(contextMessages);
        } else if (request.getHistory() != null) {
            messages.addAll(request.getHistory());
        }

        if (request.getMessage() != null && !request.getMessage().isEmpty()) {
            messages.add(Message.builder()
                    .role("user")
                    .content(request.getMessage())
                    .build());
        }

        return messages;
    }

    /**
     * 根据 agentMode 构建 system prompt 指令。
     * assistant: 通用助手，均衡回答
     * expert: 专家模式，严谨准确
     * creative: 创意模式，发散思维
     * precise: 精确模式，严格遵循
     */
    public String buildAgentModeDirective(String agentMode) {
        if (agentMode == null || agentMode.isBlank()) return null;
        return switch (agentMode) {
            case "expert" -> "【专家模式】请以严谨、专业的态度回答问题，确保信息准确无误，引用权威来源。避免不确定的推测，如不确定请明确说明。";
            case "creative" -> "【创意模式】请以富有创造力和想象力的方式回答问题，鼓励发散思维，提供新颖独特的视角和方案。";
            case "precise" -> "【精确模式】请严格遵循用户指令，输出简洁精确的结果，不添加多余的解释或修饰。";
            default -> null; // assistant 模式不需要额外指令
        };
    }

    /**
     * 根据 thinkingStyle 构建 system prompt 推理策略指令。
     * chain-of-thought: 链式推理，逐步展示思考过程
     * tree-of-thought: 树状推理，多角度分析比较
     * react: ReAct 模式，推理-行动-观察循环
     * step-by-step: 逐步分析
     * socratic: 苏格拉底式，提问引导
     */
    public String buildThinkingStyleDirective(String thinkingStyle) {
        if (thinkingStyle == null || thinkingStyle.isBlank()) return null;
        return switch (thinkingStyle) {
            case "chain-of-thought" -> "【链式推理】请逐步展示你的推理过程，先分析问题，再逐步推导，最后给出结论。用「思考：」标记推理步骤。";
            case "tree-of-thought" -> "【树状推理】请从多个角度分析问题，探索不同的解决路径，比较各路径的优劣，选择最优方案。用「分支N：」标记不同思路。";
            case "react" -> "【ReAct 模式】请使用「思考→行动→观察」循环解决问题。用「思考：」标记推理，用「行动：」标记操作，用「观察：」标记结果。";
            case "step-by-step" -> "【逐步分析】请将问题分解为清晰的步骤，逐步解决，每步标注序号。";
            case "socratic" -> "【苏格拉底式】请通过提问引导用户思考，不直接给出答案，而是用层层递进的问题帮助用户自行发现结论。";
            default -> null;
        };
    }

    /**
     * 根据 agentMode 获取 temperature 参数。
     */
    public double getTemperatureByMode(String agentMode) {
        if (agentMode == null) return 0.7;
        return switch (agentMode) {
            case "expert" -> 0.3;   // 严谨，低随机性
            case "creative" -> 0.9;  // 发散，高随机性
            case "precise" -> 0.1;   // 精确，极低随机性
            default -> 0.7;          // assistant 默认
        };
    }
}
