package com.gewu.common.entry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 入口调度文件候选（AGENTS.md / GEWU.md 单层探测产物）.
 *
 * <p>纯值对象：由各运行时（平台沙箱/桌面本地）完成文件发现与读取后组装，
 * 交给 {@link EntryFileSelector} 做跨层裁决。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryCandidate {

    /** 层级标识：global / project */
    private String level;
    /** 文件路径（展示/审计用，如 project:AGENTS.md） */
    private String path;
    /** 版本声明（已归一化；null=无版本头，不参与跨层版本比较） */
    private String version;
    /** 文件内容 */
    private String content;
}
