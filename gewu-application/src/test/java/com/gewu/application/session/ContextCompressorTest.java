package com.gewu.application.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextCompressor 单元测试。
 * <p>
 * 验证上下文压缩（当前为截断实现）的边界行为。
 * 作为 P1-15 升级为 LLM 摘要前的回归保护。
 */
class ContextCompressorTest {

    private final ContextCompressor compressor = new ContextCompressor();

    @Test
    void compress_emptyList_returnsEmptyString() {
        String result = compressor.compress(List.of());
        assertEquals("", result);
    }

    @Test
    void compress_nullList_returnsEmptyString() {
        String result = compressor.compress(null);
        assertEquals("", result);
    }

    @Test
    void compress_singleMessage_returnsFormattedMessage() {
        List<ContextCompressor.MessageView> messages = List.of(
                new ContextCompressor.MessageView("user", "Hello world")
        );

        String result = compressor.compress(messages);

        assertTrue(result.contains("[user] Hello world"));
    }

    @Test
    void compress_multipleMessages_includesAllWhenUnderLimit() {
        List<ContextCompressor.MessageView> messages = List.of(
                new ContextCompressor.MessageView("user", "Question 1"),
                new ContextCompressor.MessageView("assistant", "Answer 1"),
                new ContextCompressor.MessageView("user", "Question 2")
        );

        String result = compressor.compress(messages);

        assertTrue(result.contains("Question 1"));
        assertTrue(result.contains("Answer 1"));
        assertTrue(result.contains("Question 2"));
    }

    @Test
    void compress_overLimit_truncatesOlderMessages() {
        // MAX_COMPRESSED_LENGTH = 4000，创建超过限制的消息
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longContent.append("abcdefghij"); // 10 chars * 500 = 5000 chars per message
        }
        List<ContextCompressor.MessageView> messages = List.of(
                new ContextCompressor.MessageView("user", longContent.toString()),
                new ContextCompressor.MessageView("assistant", "recent short answer")
        );

        String result = compressor.compress(messages);

        // 结果不应超过 MAX_COMPRESSED_LENGTH
        assertTrue(result.length() <= 4000,
                "压缩结果不应超过 4000 字符，实际: " + result.length());
        // 应保留最近的消息
        assertTrue(result.contains("recent short answer"),
                "应保留最近的消息");
    }

    @Test
    void compress_preservesRolePrefix() {
        List<ContextCompressor.MessageView> messages = List.of(
                new ContextCompressor.MessageView("user", "test message")
        );

        String result = compressor.compress(messages);

        assertTrue(result.contains("[user]"), "应包含角色前缀 [user]");
        assertTrue(result.contains("[assistant]") || !result.contains("[assistant]"));
    }
}
