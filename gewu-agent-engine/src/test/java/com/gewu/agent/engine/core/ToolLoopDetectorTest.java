package com.gewu.agent.engine.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolLoopDetector} 单元测试（优化3）：连续相同签名计数、归一化比对、nudge 单次标记。
 */
@DisplayName("工具调用死循环检测器")
class ToolLoopDetectorTest {

    @Test
    @DisplayName("连续相同调用递增计数，不同调用重新起算")
    void consecutiveCounting() {
        ToolLoopDetector detector = new ToolLoopDetector();
        assertThat(detector.record("read_file", "{\"path\":\"a.txt\"}")).isEqualTo(1);
        assertThat(detector.record("read_file", "{\"path\":\"a.txt\"}")).isEqualTo(2);
        assertThat(detector.record("read_file", "{\"path\":\"a.txt\"}")).isEqualTo(3);
        // 不同签名重置计数
        assertThat(detector.record("read_file", "{\"path\":\"b.txt\"}")).isEqualTo(1);
        assertThat(detector.record("list_dir", "{\"path\":\".\"}")).isEqualTo(1);
        assertThat(detector.consecutiveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("参数键序不同、语义相同视为同一签名（归一化）")
    void normalizedSignature() {
        ToolLoopDetector detector = new ToolLoopDetector();
        assertThat(detector.record("read_file", "{\"path\":\"a.txt\",\"encoding\":\"utf8\"}")).isEqualTo(1);
        // 键序颠倒仍视为相同调用
        assertThat(detector.record("read_file", "{\"encoding\":\"utf8\",\"path\":\"a.txt\"}")).isEqualTo(2);
    }

    @Test
    @DisplayName("参数非法 JSON 时退化为原文精确比对")
    void nonJsonArgumentsFallback() {
        ToolLoopDetector detector = new ToolLoopDetector();
        assertThat(detector.record("raw_tool", "not-json")).isEqualTo(1);
        assertThat(detector.record("raw_tool", "not-json")).isEqualTo(2);
        assertThat(detector.record("raw_tool", "not-json-other")).isEqualTo(1);
    }

    @Test
    @DisplayName("nudge 标记：首次标记后 isNudged 恒真")
    void nudgeMarkedOnce() {
        ToolLoopDetector detector = new ToolLoopDetector();
        assertThat(detector.isNudged()).isFalse();
        detector.markNudged();
        assertThat(detector.isNudged()).isTrue();
        detector.markNudged();
        assertThat(detector.isNudged()).isTrue();
    }
}
