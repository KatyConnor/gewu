package com.gewu.application.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.domain.agent.AgentSkill;
import com.gewu.domain.skill.Skill;
import com.gewu.infrastructure.mapper.AgentSkillMapper;
import com.gewu.infrastructure.mapper.SkillMapper;
import com.gewu.agent.engine.message.PromptSectionContributor;
import com.gewu.agent.engine.spi.AgentSpec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技能段贡献者（AE-P2-3 收口）：把 Agent 已挂载技能的 content 以"已挂载技能"段注入 system prompt。
 * <p>单一实现点——引擎 DefaultMessageBuilder（经 PromptSectionContributor SPI 自动消费）
 * 与 wenshi 链路（直接调用 {@link #renderForAgentId}）共用同一查询与拼装逻辑，
 * 消除引擎/宿主双处维护漂移（14 号提示词清单 :71/:352 登记的债务）。
 * <p>行为对齐披露：收口后 legacy 引擎路径的 system prompt 将包含技能段（此前仅 wenshi 路径有）——
 * 双引擎 prompt 语义对齐，token 占用随挂载技能数增加。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
@Component
public class AgentSkillSectionContributor implements PromptSectionContributor {

    private final AgentSkillMapper agentSkillMapper;
    private final SkillMapper skillMapper;

    public AgentSkillSectionContributor(AgentSkillMapper agentSkillMapper, SkillMapper skillMapper) {
        this.agentSkillMapper = agentSkillMapper;
        this.skillMapper = skillMapper;
    }

    @Override
    public String sectionTitle() {
        return "已挂载技能";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String renderSection(AgentSpec agent, String userMessage) {
        return agent != null && agent.getId() != null ? renderForAgentId(agent.getId()) : null;
    }

    /**
     * 按应用层 Agent id 渲染完整技能段（含 "# 已挂载技能" 行；wenshi 链路入口）。
     * 拼装与宿主 AgentMessageBuilder 历史实现逐字节一致：标题后每个技能以空行分隔
     * （"# 已挂载技能\n\n## 技能：X\n{content}\n\n## 技能：Y..."），无有效技能时返回 null。
     */
    public String renderForAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        List<AgentSkill> agentSkills = agentSkillMapper.selectList(
                new LambdaQueryWrapper<AgentSkill>()
                        .eq(AgentSkill::getAgentId, agentId)
                        .orderByAsc(AgentSkill::getSortOrder));
        if (agentSkills.isEmpty()) {
            return null;
        }
        List<String> skillIds = agentSkills.stream().map(AgentSkill::getSkillId).toList();
        Map<String, Skill> skillMap = skillMapper.selectBatchIds(skillIds).stream()
                .collect(Collectors.toMap(Skill::getId, s -> s));
        StringBuilder entries = new StringBuilder();
        for (AgentSkill as : agentSkills) {
            Skill skill = skillMap.get(as.getSkillId());
            if (skill != null && skill.getContent() != null && !skill.getContent().isBlank()) {
                entries.append("\n\n## 技能：").append(skill.getSkillName())
                        .append("\n").append(skill.getContent());
            }
        }
        if (entries.length() == 0) {
            return null;
        }
        return "# 已挂载技能" + entries;
    }
}
