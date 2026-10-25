package com.gewu.application.workspace.dto;

import lombok.Data;

/**
 * 项目全局文本搜索命中条目（grep -n 解析）。
 */
@Data
public class GrepHitDTO {
    /** 仓库内相对路径 */
    private String path;
    /** 命中行号 */
    private int line;
    /** 命中行文本 */
    private String text;
}
