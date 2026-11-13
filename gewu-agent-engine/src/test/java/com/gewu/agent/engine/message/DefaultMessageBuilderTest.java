package com.gewu.agent.engine.message;

import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.spi.AgentSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultMessageBuilder} 段贡献者收口测试（AE-P2-3）：
 * 贡献者段以空行分隔追加、按 order 排序、null 段跳过、无贡献者行为不变。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
@DisplayName("DefaultMessageBuilder 段贡献者收口（AE-P2-3）")
class DefaultMessageBuilderTest {

    private final SystemPromptComposer composer = new SystemPromptComposer();

    private AgentSpec spec() {
        return AgentSpec.builder().id("a1").systemPrompt("基础提示词").build();
    }

    private Message systemMessage(List<Message> messages) {
        return messages.stream().filter(m -> "system".equals(m.getRole())).findFirst().orElse(null);
    }

    @Test
    @DisplayName("无贡献者：行为与旧版一致（仅 composer 段）")
    void noContributorsKeepsLegacyBehavior() {
        var builder = new DefaultMessageBuilder(composer);
        List<Message> messages = builder.buildMessages(spec(), "问题", null, null, null);

        Message system = systemMessage(messages);
        assertThat(system).isNotNull();
        assertThat(system.getContent()).isEqualTo("基础提示词");
    }

    @Test
    @DisplayName("贡献者段以空行分隔追加，按 order 排序；null 段跳过")
    void contributorSectionsAppendedInOrder() {
        PromptSectionContributor low = new PromptSectionContributor() {
            @Override public String sectionTitle() { return "低序段"; }
            @Override public int order() { return 10; }
            @Override public String renderSection(AgentSpec agent, String userMessage) {
                return "# 低序段\n\n内容A";
            }
        };
        PromptSectionContributor skipped = new PromptSectionContributor() {
            @Override public String sectionTitle() { return "跳过段"; }
            @Override public String renderSection(AgentSpec agent, String userMessage) { return null; }
        };
        PromptSectionContributor high = new PromptSectionContributor() {
            @Override public String sectionTitle() { return "高序段"; }
            @Override public int order() { return 200; }
            @Override public String renderSection(AgentSpec agent, String userMessage) {
                return "# 高序段\n\n内容B";
            }
        };

        var builder = new DefaultMessageBuilder(composer, List.of(high, skipped, low));
        List<Message> messages = builder.buildMessages(spec(), "问题", null, null, null);

        Message system = systemMessage(messages);
        assertThat(system).isNotNull();
        // composer 段在前 + 空行分隔 + order 排序（低序段先于高序段）+ null 段跳过
        assertThat(system.getContent()).isEqualTo("基础提示词\n\n# 低序段\n\n内容A\n\n# 高序段\n\n内容B");
    }

    @Test
    @DisplayName("直连对话（agent=null）：贡献者自决跳过则无 system 段")
    void nullAgentContributorSkips() {
        PromptSectionContributor skipOnNullAgent = new PromptSectionContributor() {
            @Override public String sectionTitle() { return "技能"; }
            @Override public String renderSection(AgentSpec agent, String userMessage) {
                return agent != null && agent.getId() != null ? "# 技能\n\n内容" : null;
            }
        };
        var builder = new DefaultMessageBuilder(composer, List.of(skipOnNullAgent));
        List<Message> messages = builder.buildMessages(null, "问题", null, null, null);

        // composer 对 null agent 无基础段 + 贡献者跳过 → 无 system 消息（旧行为）
        assertThat(messages)
                .filteredOn(m -> "system".equals(m.getRole()))
                .isEmpty();
    }
}
