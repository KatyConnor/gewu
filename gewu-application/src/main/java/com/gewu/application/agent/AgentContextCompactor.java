package com.gewu.application.agent;

import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.spi.ContextCompactor;
import com.gewu.application.session.ContextCompressor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 引擎上下文压缩器宿主实现（配额与上下文自治）：
 * 复用 {@link ContextCompressor}（LLM 摘要 + 截断回退）把较早的历史压缩为一条摘要消息，
 * 保留系统提示与最近 keepRecentRounds 轮（一轮 ≈ assistant + 其 tool 消息）。
 * 摘要失败/压缩无收益时返回 null（引擎放行，任务继续）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentContextCompactor implements ContextCompactor {

    /** 摘要消息长度上限（与 ContextCompressor 内部截断一致） */
    private static final int SUMMARY_MAX_CHARS = 4000;

    private final ContextCompressor contextCompressor;

    @Override
    public List<Message> compact(List<Message> messages, int keepRecentRounds) {
        if (messages == null || messages.isEmpty() || keepRecentRounds <= 0) {
            return null;
        }
        try {
            int keepMessages = Math.max(2, keepRecentRounds * 3);
            if (messages.size() <= keepMessages + 2) {
                // 可压缩空间不足
                return null;
            }
            boolean hasSystem = "system".equals(messages.get(0).getRole());
            int headKeep = hasSystem ? 1 : 0;
            int compactEnd = messages.size() - keepMessages;
            if (compactEnd <= headKeep) {
                return null;
            }

            // 待压缩区：系统提示之后、保留区之前的旧消息（tool 消息剔除——摘要语义不完整）
            List<ContextCompressor.MessageView> views = new ArrayList<>();
            for (Message m : messages.subList(headKeep, compactEnd)) {
                if ("tool".equals(m.getRole())) {
                    continue;
                }
                views.add(new ContextCompressor.MessageView(m.getRole(), m.getContent()));
            }
            if (views.isEmpty()) {
                return null;
            }
            String summary = contextCompressor.compress(views);
            if (summary == null || summary.isBlank()) {
                return null;
            }
            if (summary.length() > SUMMARY_MAX_CHARS) {
                summary = summary.substring(0, SUMMARY_MAX_CHARS);
            }

            // 新列表 = 系统提示 + 摘要 + 最近 N 轮
            List<Message> result = new ArrayList<>();
            if (hasSystem) {
                result.add(messages.get(0));
            }
            result.add(Message.builder()
                    .role("user")
                    .content("【对话历史摘要】以下是本次任务较早阶段已完成交互的摘要，"
                            + "其后的工具结果与结论仍然有效：\n" + summary)
                    .build());
            result.addAll(new ArrayList<>(messages.subList(compactEnd, messages.size())));
            log.info("引擎上下文压缩: {} 条 → {} 条（摘要 {} 字）", messages.size(), result.size(), summary.length());
            return result;
        } catch (Exception e) {
            log.warn("引擎上下文压缩失败（放行继续）: cause={}", e.getMessage());
            return null;
        }
    }
}
