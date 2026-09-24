package com.gewu.application.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * {@link SessionFileWorkspaceService#turnDiffCounts} 回合增删统计单元测试。
 * 该口径同时驱动回合汇总条（metadata.turnFiles / GET file-changes/turn）与撤销范围。
 */
@DisplayName("回合文件增删统计")
class SessionFileWorkspaceTurnDiffTest {

    @Test
    @DisplayName("回合内修改既有文件：LCS 增删统计")
    void modifiedFile() {
        assertArrayEquals(new int[]{1, 1}, SessionFileWorkspaceService.turnDiffCounts("a\nb\nc", "a\nB\nc"));
    }

    @Test
    @DisplayName("回合内新建文件：before 为空全部计入新增")
    void createdFile() {
        assertArrayEquals(new int[]{3, 0}, SessionFileWorkspaceService.turnDiffCounts(null, "x\ny\nz"));
        assertArrayEquals(new int[]{3, 0}, SessionFileWorkspaceService.turnDiffCounts("", "x\ny\nz"));
    }

    @Test
    @DisplayName("回合内清空既有文件：全部计入删除")
    void emptiedFile() {
        assertArrayEquals(new int[]{0, 3}, SessionFileWorkspaceService.turnDiffCounts("a\nb\nc", ""));
    }

    @Test
    @DisplayName("文件被外部删除：按整文件删除统计")
    void externallyDeleted() {
        assertArrayEquals(new int[]{0, 2}, SessionFileWorkspaceService.turnDiffCounts("a\nb", null));
    }

    @Test
    @DisplayName("无变化：+0 -0（汇总条跳过该文件）")
    void noChange() {
        assertArrayEquals(new int[]{0, 0}, SessionFileWorkspaceService.turnDiffCounts("a\nb", "a\nb"));
    }

    @Test
    @DisplayName("尾部换行不计入行数（与变更列表口径一致）")
    void trailingNewline() {
        assertArrayEquals(new int[]{2, 0}, SessionFileWorkspaceService.turnDiffCounts(null, "a\nb\n"));
    }
}
