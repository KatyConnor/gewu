package com.gewu.application.workspace.dto;

import lombok.Data;

/**
 * reflog 条目（误操作恢复数据源，最新在前）。
 */
@Data
public class ReflogEntryDTO {
    private String sha;
    private String shortSha;
    /** reflog 选择器（HEAD@{0}） */
    private String selector;
    /** 操作描述（reset/commit/checkout 等） */
    private String subject;
}
