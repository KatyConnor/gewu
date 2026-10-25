package com.gewu.application.workspace.dto;

import lombok.Data;

/**
 * Git stash 记录条目。
 */
@Data
public class GitStashDTO {
    /** stash 序号（stash@{N} 的 N） */
    private int index;
    private String message;
}
