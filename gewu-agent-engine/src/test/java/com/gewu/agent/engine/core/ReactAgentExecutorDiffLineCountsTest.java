package com.gewu.agent.engine.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReactAgentExecutor#diffLineCounts} 行级增删统计单元测试。
 * 该口径同时驱动 edit_file 结果文案（第 N 行起，+A -D）与前端过程时间线差异块。
 */
@DisplayName("edit_file 行级增删统计")
class ReactAgentExecutorDiffLineCountsTest {

    @Test
    @DisplayName("中段替换：公共前后缀裁剪后 +1 -1")
    void middleReplacement() {
        assertThat(ReactAgentExecutor.diffLineCounts("a\nb\nc", "a\nB\nc")).containsExactly(1, 1);
    }

    @Test
    @DisplayName("纯新增：尾部追加两行")
    void pureAddition() {
        assertThat(ReactAgentExecutor.diffLineCounts("a\nb", "a\nb\nc\nd")).containsExactly(2, 0);
    }

    @Test
    @DisplayName("纯删除：整块移除")
    void pureDeletion() {
        assertThat(ReactAgentExecutor.diffLineCounts("a\nb\nc", "a")).containsExactly(0, 2);
    }

    @Test
    @DisplayName("无变化：+0 -0")
    void noChange() {
        assertThat(ReactAgentExecutor.diffLineCounts("a\nb\n", "a\nb\n")).containsExactly(0, 0);
    }

    @Test
    @DisplayName("多块交错：中段 LCS 计公共行")
    void interleavedBlocks() {
        assertThat(ReactAgentExecutor.diffLineCounts("x\n1\ny\n2\nz", "x\n9\ny\n8\nz")).containsExactly(2, 2);
    }

    @Test
    @DisplayName("尾部空行：split 保留语义（末行空行计入）")
    void trailingNewline() {
        assertThat(ReactAgentExecutor.diffLineCounts("a\n", "a\n\n")).containsExactly(1, 0);
    }

    @Test
    @DisplayName("行内多处公共子串：LCS 取最长公共行序列")
    void duplicatedLines() {
        // 中段 old=[a,b,a] new=[a,x,a]：LCS=[a,a] → +1 -1
        assertThat(ReactAgentExecutor.diffLineCounts("a\nb\na", "a\nx\na")).containsExactly(1, 1);
    }
}
