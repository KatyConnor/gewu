package com.gewu.common.diff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DiffUtil} 单测（58 号 §10 P3-4 二期）.
 */
class DiffUtilTest {

    @Test
    @DisplayName("相同内容返回空串")
    void identical_empty() {
        assertThat(DiffUtil.unifiedDiff("a\nb\nc", "a\nb\nc", 400)).isEmpty();
    }

    @Test
    @DisplayName("单行修改生成 +/- 对")
    void singleChange() {
        String diff = DiffUtil.unifiedDiff("a\n旧内容\nc", "a\n新内容\nc", 400);
        assertThat(diff).contains("@@").contains("-旧内容").contains("+新内容");
    }

    @Test
    @DisplayName("前缀+后缀裁剪：只渲染差异中段")
    void prefixSuffixTrimmed() {
        String common = "common\n";
        String before = "x\n" + common.repeat(10) + "OLD\n" + common.repeat(10);
        String after = "x\n" + common.repeat(10) + "NEW\n" + common.repeat(10);
        String diff = DiffUtil.unifiedDiff(before, after, 400);
        assertThat(diff).contains("-OLD").contains("+NEW");
        // 上下文 3 行：common 行不应全量出现
        assertThat(countOccurrences(diff, "common")).isLessThan(20);
    }

    @Test
    @DisplayName("纯新增与纯删除")
    void pureAddAndDelete() {
        String add = DiffUtil.unifiedDiff("a", "a\nb\nc", 400);
        assertThat(add).contains("+b").contains("+c");
        String del = DiffUtil.unifiedDiff("a\nb\nc", "a", 400);
        assertThat(del).contains("-b").contains("-c");
    }

    @Test
    @DisplayName("行号正确（1-based，含公共前缀偏移）")
    void lineNumbers() {
        // 公共前缀 3 行（l1/l2/l3），第 4 行（OLD）被改
        String diff = DiffUtil.unifiedDiff("l1\nl2\nl3\nOLD", "l1\nl2\nl3\nNEW", 400);
        assertThat(diff).contains("@@ -4,1 +4,1 @@");
    }

    @Test
    @DisplayName("中段超限降级为摘要")
    void oversize_degradesToSummary() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            big.append("旧").append(i).append('\n');
        }
        String diff = DiffUtil.unifiedDiff(big.toString(), "新内容\n", 400);
        assertThat(diff).contains("内容差异过大").contains("删除 3000 行");
    }

    @Test
    @DisplayName("输出行数上限截断")
    void outputCapped() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            a.append("旧").append(i).append('\n');
            b.append("新").append(i).append('\n');
        }
        String diff = DiffUtil.unifiedDiff(a.toString(), b.toString(), 50);
        assertThat(diff).contains("diff 超过 50 行截断");
    }

    @Test
    @DisplayName("空对非空：全部新增")
    void emptyToContent() {
        String diff = DiffUtil.unifiedDiff("", "a\nb", 400);
        assertThat(diff).contains("+a").contains("+b");
    }

    @Test
    @DisplayName("归一化边界：尾部无换行")
    void trailingWithoutNewline() {
        String diff = DiffUtil.unifiedDiff("a\nOLD", "a\nNEW", 400);
        assertThat(diff).contains("-OLD").contains("+NEW");
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0, idx = 0;
        while ((idx = s.indexOf(sub, idx)) >= 0) {
            n++;
            idx += sub.length();
        }
        return n;
    }
}
