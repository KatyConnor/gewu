package com.gewu.application.workspace.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 双分支对比结果（本地 git compare，MR 前预览数据源）。
 */
@Data
public class CompareDTO {
    private String base;
    private String head;
    /** head 独有提交（base..head，最多 50 条） */
    private List<com.gewu.application.workspace.dto.GitLogEntryDTO> commits = new ArrayList<>();
    /** diff --stat 全文 */
    private String statText;
}
