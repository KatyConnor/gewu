package com.gewu.agent.engine.spi;

import com.gewu.agent.engine.llm.model.Message;

import java.util.List;

/**
 * 上下文压缩器 SPI（auto-compact，配额与上下文自治）：
 * 估算上下文 tokens 接近模型上下文窗口时，把较早的历史压缩为摘要，
 * 保留系统提示与最近的对话轮次，压缩后任务继续（不再因 token 熔断终止）。
 * <p>引擎传入 keepRecentRounds（保留的最近推理轮数），宿主实现决定具体的
 * 摘要与保留策略；返回 null/原列表表示放弃压缩（引擎照常继续）。
 *
 * @since 1.0.0
 */
public interface ContextCompactor {

    /**
     * 压缩对话历史。
     *
     * @param messages         当前完整对话（含系统提示/assistant/tool 消息），不可变看待
     * @param keepRecentRounds 建议保留的最近推理轮数（一轮 = assistant + 其 tool 消息）
     * @return 压缩后的消息列表（新列表，不修改入参）；null 表示放弃压缩
     */
    List<Message> compact(List<Message> messages, int keepRecentRounds);
}
