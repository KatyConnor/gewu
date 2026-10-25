package com.gewu.application.workspace.dto;

import lombok.Data;

import java.util.List;

/**
 * Git 常规操作统一命令体（IDEA 式 Git 菜单操作集）。
 * 不同操作按需取字段，未用字段为 null。
 */
@Data
public class GitOpCommand {
    /** 提交信息（commit/stash） */
    private String message;
    /** 提交文件（commit：勾选的变更文件；null/空=只提交已暂存内容） */
    private List<String> files;
    /** 回滚的已跟踪文件（rollback） */
    private List<String> paths;
    /** 回滚的未跟踪文件（rollback，git clean 清除） */
    private List<String> untrackedPaths;
    /** 分支名（branch 创建/branch-delete） */
    private String name;
    /** 目标分支（checkout/merge） */
    private String branch;
    /** stash 序号（stash-pop） */
    private Integer index;
    /** 创建后立即检出（branch） */
    private Boolean checkout;
    /** 追加上一次提交（commit --amend） */
    private Boolean amend;
}
