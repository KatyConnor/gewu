package com.gewu.application.workspace.dto;

import lombok.Data;

/**
 * 调试操作统一命令体（IDEA 式 Debug 菜单/工具条动作）。
 * 不同端点按需取字段，未用字段为 null。
 */
@Data
public class DebugOpCommand {
    /** 启动调试的运行命令（start；默认 mvn spring-boot:run） */
    private String command;
    /** 启动时目标 JVM 是否等待调试器挂起（start；默认 false） */
    private Boolean suspend;
    /** 源文件工作空间相对路径（breakpoint） */
    private String path;
    /** 断点行号（breakpoint） */
    private Integer line;
    /** 删除断点（breakpoint；默认添加） */
    private Boolean remove;
    /** 断点条件表达式（breakpoint；命中时求值为 true 才挂起，空=无条件） */
    private String condition;
    /** 执行控制（action）：cont/next/step/stepUp */
    private String op;
    /** 求值表达式（eval） */
    private String expr;
    /** 线程切换目标（action op=thread：jdb 0x1 / dlv goroutine 编号 / gdb 线程号） */
    private String threadId;
    /** 启动模式（start）：launch 重新启动（默认）/ attach 附加已运行进程（仅 Java，需应用已开调试端口） */
    private String mode;
}
