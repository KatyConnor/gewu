package com.gewu.desktop.adapter;

import com.gewu.agent.engine.tool.FileWorkspaceSpi;
import com.gewu.agent.engine.tool.ToolContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地文件工作空间 SPI（D1-15，ADR-009）：引擎内置文件工具（read_file/write_file/edit_file/list_dir）
 * 的桌面执行后端——平台侧挂 docker 沙箱，桌面侧挂本地文件系统。
 * <p>available()=true 使引擎自动注册四个内置工具；edit_file 的唯一匹配与 +N/-N 统计由引擎
 * 内部实现（ADR-009：工具命名/结果文案与平台逐字一致，UI 零适配）。
 * 安全三件套（PathGuard/写授权/审计）与变更记录均在 {@link LocalFileService}（SPI 与
 * 文件变更端点共享唯一写入口）。
 *
 * @since 1.0.0
 */
@Component
public class LocalFileWorkspaceSpi implements FileWorkspaceSpi {

    private final LocalFileService fileService;

    public LocalFileWorkspaceSpi(LocalFileService fileService) {
        this.fileService = fileService;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String readFile(ToolContext ctx, String path) throws Exception {
        return fileService.readFile(ctx, path);
    }

    @Override
    public void writeFile(ToolContext ctx, String path, String content) throws Exception {
        fileService.writeFile(ctx, path, content);
    }

    @Override
    public List<String> listDir(ToolContext ctx, String path) throws Exception {
        return fileService.listDir(ctx, path);
    }
}
