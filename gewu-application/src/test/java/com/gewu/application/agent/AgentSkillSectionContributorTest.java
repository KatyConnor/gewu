package com.gewu.application.agent;

import com.gewu.domain.agent.AgentSkill;
import com.gewu.domain.skill.Skill;
import com.gewu.infrastructure.mapper.AgentSkillMapper;
import com.gewu.infrastructure.mapper.SkillMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link AgentSkillSectionContributor} AE-P2-3 测试：技能段单一实现点的拼装
 * 与宿主历史实现逐字节一致（"# 已挂载技能\n\n## 技能：X\n{content}\n\n## 技能：Y..."）。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("技能段贡献者（AE-P2-3 收口）")
class AgentSkillSectionContributorTest {

    @Mock
    private AgentSkillMapper agentSkillMapper;
    @Mock
    private SkillMapper skillMapper;

    private AgentSkillSectionContributor contributor;

    @BeforeEach
    void setUp() {
        contributor = new AgentSkillSectionContributor(agentSkillMapper, skillMapper);
    }

    private AgentSkill mounted(String skillId) {
        AgentSkill as = new AgentSkill();
        as.setAgentId("a1");
        as.setSkillId(skillId);
        return as;
    }

    private Skill skill(String id, String name, String content) {
        Skill s = new Skill();
        s.setId(id);
        s.setSkillName(name);
        s.setContent(content);
        return s;
    }

    @Test
    @DisplayName("多技能拼装：与宿主历史实现逐字节一致（标题+空行分隔的技能条目）")
    void rendersByteIdenticalSection() {
        when(agentSkillMapper.selectList(any())).thenReturn(List.of(mounted("k1"), mounted("k2")));
        when(skillMapper.selectBatchIds(any())).thenReturn(List.of(
                skill("k1", "代码审查", "按 P0-P3 分级输出"),
                skill("k2", "安全审计", "按 OWASP 分类")));

        String section = contributor.renderForAgentId("a1");

        assertNotNull(section);
        assertEquals("# 已挂载技能\n\n"
                + "## 技能：代码审查\n按 P0-P3 分级输出\n\n"
                + "## 技能：安全审计\n按 OWASP 分类", section);
    }

    @Test
    @DisplayName("无挂载/空内容/空 id：返回 null（不出段）")
    void emptyMountReturnsNull() {
        when(agentSkillMapper.selectList(any())).thenReturn(List.of());
        assertNull(contributor.renderForAgentId("a1"));

        when(agentSkillMapper.selectList(any())).thenReturn(List.of(mounted("k1")));
        when(skillMapper.selectBatchIds(any())).thenReturn(List.of(skill("k1", "空技能", "  ")));
        assertNull(contributor.renderForAgentId("a1"));
        assertNull(contributor.renderForAgentId(null));
    }

    @Test
    @DisplayName("SPI 渲染：agent 为 null（直连）返回 null；有 id 时委托 renderForAgentId")
    void spiRenderDelegatesByAgentId() {
        when(agentSkillMapper.selectList(any())).thenReturn(List.of(mounted("k1")));
        when(skillMapper.selectBatchIds(any())).thenReturn(List.of(skill("k1", "代码审查", "内容")));

        com.gewu.agent.engine.spi.AgentSpec spec = com.gewu.agent.engine.spi.AgentSpec.builder()
                .id("a1").build();
        assertNotNull(contributor.renderSection(spec, "问题"));
        assertNull(contributor.renderSection(null, "问题"));
        assertEquals("已挂载技能", contributor.sectionTitle());
    }
}
