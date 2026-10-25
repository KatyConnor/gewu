package com.gewu.application.workspace.dto;

import lombok.Data;

/**
 * Git 提交历史条目（git log 单行格式解析）。
 */
@Data
public class GitLogEntryDTO {
    /** 完整 commit SHA */
    private String hash;
    /** 7 位短 SHA */
    private String shortHash;
    private String author;
    /** 提交时间（epoch 秒） */
    private Long timestamp;
    private String message;
}
