package com.gewu.desktop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桌面运行时配置（gewu.desktop.*）。
 * <p>模型 Key 经此注入（开发态 yml / 生产态 Electron 主进程经 stdin 引导注入），
 * 仅驻内存不落盘（docs/design/42 §11.1）。
 *
 * @since 1.0.0
 */
@Component
@ConfigurationProperties(prefix = "gewu.desktop")
public class DesktopProps {

    /** 模型供应商 API Key（providerCode → 明文 Key，仅内存） */
    private Map<String, String> modelKeys = new LinkedHashMap<>();

    /** 历史上下文条数上限（对齐引擎 defaultHistoryLimit 语义） */
    private int historyLimit = 50;

    /** 数据目录（H2 文件位置；Electron 态指向 userData） */
    private String dataDir = "./data";

    /** 工作区根目录（文件工具的沙箱边界；会话 directory 列可覆盖） */
    private String workspaceRoot = "./workspace";
    /** 写授权等待上限(分钟):超时后引擎按拒绝收尾;前端挂起点(30 分钟)应小于此值,
     *  保证用户回点「授权并继续」时引擎侧仍在等待——决策成功即无缝恢复执行 */
    private int approvalTimeoutMinutes = 35;

    /** 权限模式：READ_ONLY / WORKSPACE_WRITE（默认，工作区内写自动授权+审计）/ FULL_AUTO */
    private String permissionMode = "WORKSPACE_WRITE";

    /** 入口调度指令注入总量上限（字节，AGENTS.md/GEWU.md 裁决产物；设计文档 56 §5.1） */
    private int entryMaxBytes = 32 * 1024;

    public Map<String, String> getModelKeys() {
        return modelKeys;
    }

    public void setModelKeys(Map<String, String> modelKeys) {
        this.modelKeys = modelKeys;
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = historyLimit;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public int getApprovalTimeoutMinutes() {
        return approvalTimeoutMinutes;
    }

    public void setApprovalTimeoutMinutes(int approvalTimeoutMinutes) {
        this.approvalTimeoutMinutes = approvalTimeoutMinutes;
    }

    public String getPermissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(String permissionMode) {
        this.permissionMode = permissionMode;
    }

    public int getEntryMaxBytes() {
        return entryMaxBytes;
    }

    public void setEntryMaxBytes(int entryMaxBytes) {
        this.entryMaxBytes = entryMaxBytes;
    }
}
