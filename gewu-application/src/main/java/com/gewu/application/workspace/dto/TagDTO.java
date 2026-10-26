package com.gewu.application.workspace.dto;

import lombok.Data;

/**
 * Git 标签条目（git for-each-ref 解析）。
 */
@Data
public class TagDTO {
    private String name;
    /** 创建时间（creatordate，格式 yyyy-MM-dd HH:mm） */
    private String date;
    /** 短 SHA */
    private String sha;
}
