package com.gewu.agent.engine.message;

import com.gewu.agent.engine.spi.AgentSpec;

/**
 * system prompt 段贡献者 SPI（AE-P2-3 收口）——技能段等附加段的单一组装协议。
 * <p>背景：技能段注入此前由引擎 DefaultMessageBuilder（不含）与应用层组装器（含）双处维护，
 * 新增段时极易漂移。收口后：附加段实现本 SPI 注册为 Bean，{@link DefaultMessageBuilder}
 * 统一按 order 排序、以空行分隔追加；未实现任何贡献者时行为与旧版一致。
 * <p>契约：<b>{@link #renderSection} 返回完整 Markdown 段（含 "# 标题" 行）</b>，
 * 调用方仅负责段间空行分隔——段内格式（标题与内容的间距等）由贡献者全权定义，
 * 保证迁移前后逐字节一致。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-11-13
 */
public interface PromptSectionContributor {

    /** 段标题（元数据用途：目录/日志/诊断；段内容格式由 renderSection 全权定义） */
    String sectionTitle();

    /**
     * 渲染完整 Markdown 段（含 "# 标题" 行）。
     *
     * @param agent      Agent 配置（可为 null——直连对话模式，贡献者自行决定是否出段）
     * @param userMessage 用户消息
     * @return 完整段内容；null/空白 = 本段跳过
     */
    String renderSection(AgentSpec agent, String userMessage);

    /** 排序（小者在前） */
    default int order() {
        return 100;
    }
}
