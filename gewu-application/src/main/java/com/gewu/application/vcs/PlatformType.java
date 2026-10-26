package com.gewu.application.vcs;

/**
 * 托管平台类型（协作层适配目标）。
 * 已知域名自动推断；自建实例按 host 启发式识别（gitlab/gitea/gogs/gitee/github 关键字），
 * 后续版本支持项目级显式配置。
 */
public enum PlatformType {
    GITHUB, GITLAB, GITEE, GITEA;

    /** 从仓库 host 推断平台类型；无法识别返回 null（协作功能降级） */
    public static PlatformType detect(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String h = host.toLowerCase();
        if (h.equals("github.com") || h.endsWith(".github.com") || h.contains("github")) {
            return GITHUB;
        }
        if (h.equals("gitee.com") || h.endsWith(".gitee.com") || h.contains("gitee")) {
            return GITEE;
        }
        if (h.equals("gitlab.com") || h.endsWith(".gitlab.com") || h.contains("gitlab")) {
            return GITLAB;
        }
        if (h.contains("gitea") || h.contains("gogs")) {
            return GITEA;
        }
        return null;
    }
}
