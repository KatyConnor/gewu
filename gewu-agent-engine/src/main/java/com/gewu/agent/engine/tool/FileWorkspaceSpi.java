package com.gewu.agent.engine.tool;

/**
 * 文件工作空间 SPI（S9 F3）——引擎内置文件工具（read_file/write_file/edit_file/list_dir）
 * 的执行后端。引擎层不感知沙箱实现；由应用层提供基于 SandboxClient 的实现，
 * 按 {@link ToolContext#getSessionId()} 路由到会话绑定的项目仓库目录或用户默认空间。
 * <p>路径均为相对路径（相对会话工作空间根目录），实现方负责拒绝 {@code ..} 越界。
 *
 * @since 1.0.0
 */
public interface FileWorkspaceSpi {

    /**
     * 是否可用（NoOp 实现返回 false，引擎将不注册文件工具）。
     */
    default boolean available() {
        return true;
    }

    /**
     * 读取文件内容。
     *
     * @return 文件内容；文件不存在返回 null
     */
    String readFile(ToolContext ctx, String path) throws Exception;

    /**
     * 写入文件（整体覆盖）。实现方需在首次修改该路径前记录 before 快照。
     */
    void writeFile(ToolContext ctx, String path, String content) throws Exception;

    /**
     * 列出目录内容（文件与子目录名）。
     */
    java.util.List<String> listDir(ToolContext ctx, String path) throws Exception;
}
