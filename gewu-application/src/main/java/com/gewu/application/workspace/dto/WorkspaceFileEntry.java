package com.gewu.application.workspace.dto;

/**
 * 开发工作空间文件树条目（结构化目录列表，替代前端解析 ls 原文）。
 */
public record WorkspaceFileEntry(String name, boolean dir, long size, long mtime) {
}
