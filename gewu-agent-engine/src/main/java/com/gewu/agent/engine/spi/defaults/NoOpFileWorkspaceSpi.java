package com.gewu.agent.engine.spi.defaults;

import com.gewu.agent.engine.tool.FileWorkspaceSpi;
import com.gewu.agent.engine.tool.ToolContext;

import java.util.List;

/**
 * 文件工作空间 SPI 缺省实现（S9 F3）：未配置沙箱实现时禁用文件工具
 * （available=false，引擎不向模型注册 read_file/write_file 等工具）。
 *
 * @since 1.0.0
 */
public class NoOpFileWorkspaceSpi implements FileWorkspaceSpi {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String readFile(ToolContext ctx, String path) {
        return null;
    }

    @Override
    public void writeFile(ToolContext ctx, String path, String content) {
        throw new UnsupportedOperationException("文件工作空间未配置");
    }

    @Override
    public List<String> listDir(ToolContext ctx, String path) {
        return List.of();
    }
}
