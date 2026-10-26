package com.gewu.application.vcs.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Release 统一模型（基于已推送的 tag 创建，四平台均有 API）。
 */
@Data
public class ReleaseDTO {
    /** 平台侧 Release id（附件上传用；GitLab 链接式无 id） */
    private Long id;
    private String tagName;
    private String name;
    private String notes;
    /** open / published / draft */
    private String state;
    private boolean prerelease;
    private String webUrl;
    private Long createdAt;
    private List<String> assets = new ArrayList<>();
}
