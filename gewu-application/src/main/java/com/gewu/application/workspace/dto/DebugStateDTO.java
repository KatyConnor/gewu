package com.gewu.application.workspace.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 调试会话状态快照（jdb 输出解析结果，IDEA 调试面板数据源）。
 */
@Data
public class DebugStateDTO {

    /** inactive 未启动 | running 运行中 | stopped 已挂起（断点/步进停点）| terminated 已结束 */
    private String status;
    /** 调试语言驱动：java | python | node */
    private String kind;
    /** 最近一次调试动作的原始输出（断点设置反馈/求值结果/错误信息） */
    private String message;
    private String currentClass;
    private String currentMethod;
    /** 当前停点源文件名（如 Main.java） */
    private String currentFile;
    private Integer currentLine;
    private String thread;
    /** 被调试应用控制台增量输出 */
    private String console;
    private List<Frame> frames = new ArrayList<>();
    private List<Local> locals = new ArrayList<>();
    private List<Watch> watches = new ArrayList<>();
    private List<Breakpoint> breakpoints = new ArrayList<>();
    private List<Thread> threads = new ArrayList<>();
    /** 当前选中线程（threadId） */
    private String currentThread;

    /** 线程/协程条目（IDEA Threads 视图精简版） */
    @Data
    public static class Thread {
        /** 线程标识（jdb 0x1 / dlv 编号 / gdb 线程号） */
        private String id;
        private String name;
        /** 是否当前调试上下文 */
        private boolean current;
        /** 状态描述（running/suspended 等，可空） */
        private String statusDesc;
    }

    /** 调用栈帧 */
    @Data
    public static class Frame {
        private String className;
        private String methodName;
        private String fileName;
        private Integer line;
    }

    /** 顶层栈帧局部变量 */
    @Data
    public static class Local {
        private String name;
        private String value;
    }

    /** Watch 表达式及其挂起态取值 */
    @Data
    public static class Watch {
        private String expr;
        private String value;
    }

    /** 已登记断点 */
    @Data
    public static class Breakpoint {
        private String path;
        private int line;
        private String className;
        /** 条件表达式（空=无条件） */
        private String condition;
    }
}
