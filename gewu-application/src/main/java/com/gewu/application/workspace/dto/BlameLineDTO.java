package com.gewu.application.workspace.dto;

import lombok.Data;

/**
 * 行级归属条目（git blame --line-porcelain 解析，IDEA Annotate 数据源）。
 */
@Data
public class BlameLineDTO {
    /** 文件行号（1 起） */
    private int line;
    /** 短 SHA */
    private String sha;
    private String author;
    /** 提交时间（epoch 毫秒） */
    private Long time;
    private String summary;
    private String content;
}
