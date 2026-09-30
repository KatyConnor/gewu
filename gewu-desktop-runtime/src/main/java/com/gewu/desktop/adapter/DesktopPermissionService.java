package com.gewu.desktop.adapter;

import com.gewu.agent.engine.spi.PermissionResult;
import com.gewu.agent.engine.spi.PermissionService;
import com.gewu.desktop.config.DesktopProps;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 桌面权限服务（D1-20 最小版）：引擎 PermissionService SPI 的本地实现，
 * 承载三级权限模式（§11.2，对标 ZCode）。
 * <p>当前 MVP 决策源为同步自动授权/拒绝（工作区内写 + 审计留痕）；
 * 弹窗式授权（HitlGateway → 渲染层弹窗）在 D1-21 接入，届时决策源切换为交互流。
 *
 * @since 1.0.0
 */
@Component
public class DesktopPermissionService implements PermissionService {

    /** 只读工具（自动放行） */
    private static final Set<String> READ_TOOLS = Set.of(
            "read_file", "list_dir", "fs.read", "fs.glob", "fs.grep",
            "git.status", "git.diff", "git.log", "project.context", "web.search", "plan_task");

    /** 写类工具（受权限模式约束） */
    private static final Set<String> WRITE_TOOLS = Set.of(
            "write_file", "edit_file", "fs.write", "fs.edit", "shell.exec", "code_execute");

    private final DesktopProps props;

    public DesktopPermissionService(DesktopProps props) {
        this.props = props;
    }

    @Override
    public PermissionResult evaluate(String agentId, String toolName, String resource) {
        String mode = props.getPermissionMode();
        boolean isWrite = WRITE_TOOLS.contains(toolName);
        boolean isRead = READ_TOOLS.contains(toolName);

        if ("READ_ONLY".equals(mode)) {
            if (isWrite) {
                return PermissionResult.deny("当前为只读模式（READ_ONLY），写入类操作被拒绝");
            }
            return PermissionResult.allow();
        }
        // WORKSPACE_WRITE / FULL_AUTO：工作区内写自动授权（写路径审计由 LocalFileService 落账；
        // shell/code_execute 的具体命令校验在各自工具实现 + 引擎安全链内完成）
        if (isWrite || isRead) {
            return PermissionResult.allow();
        }
        // 未知工具（DB 配置型/MCP）：默认放行，交由引擎安全链与授权弹窗（D1-21）约束
        return PermissionResult.allow();
    }
}
