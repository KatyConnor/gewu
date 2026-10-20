package com.gewu.common.entry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 入口调度文件裁决结果.
 *
 * <p>reason 枚举（设计文档 56 §5.2）：
 * no-entry / only-candidate / version-higher / project-default。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntrySelection {

    /** 生效来源标签（提示词展示用，如 project:AGENTS.md@1.3.0 / user-global:GEWU.md / none） */
    private String source;
    /** 生效文件路径（none 时为 null） */
    private String path;
    /** 生效版本（none/无版本头时为 null） */
    private String version;
    /** 裁决原因 */
    private String reason;
    /** 生效内容（none 时为 null） */
    private String content;
}
