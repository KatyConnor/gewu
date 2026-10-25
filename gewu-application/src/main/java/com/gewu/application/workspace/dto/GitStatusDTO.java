package com.gewu.application.workspace.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Git 工作区状态（git status --porcelain=v1 -b 解析结果）。
 */
@Data
public class GitStatusDTO {

    private String branch;
    /** 上游分支（如 origin/main），无跟踪时为空 */
    private String upstream;
    private int ahead;
    private int behind;
    private List<Change> changes = new ArrayList<>();

    /** 单条变更（已按暂存/未跟踪归类） */
    @Data
    public static class Change {
        /** 显示路径（重命名取新路径） */
        private String path;
        /** 重命名来源路径（非重命名为空） */
        private String renameFrom;
        /** 已暂存（含新增/删除/重命名暂存态） */
        private boolean staged;
        /** 未跟踪新文件 */
        private boolean untracked;
        /** 状态中文描述：新增/修改/删除/重命名 */
        private String statusDesc;
    }
}
