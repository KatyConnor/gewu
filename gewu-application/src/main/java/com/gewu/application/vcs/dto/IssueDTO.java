package com.gewu.application.vcs.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Issue 统一模型（跨平台归一；编号空间独立于 MR）。
 */
@Data
public class IssueDTO {
    private long number;
    private String title;
    private String description;
    /** open / closed */
    private String state;
    private String webUrl;
    private String author;
    private Long createdAt;
    private List<Comment> comments = new ArrayList<>();

    @Data
    public static class Comment {
        private String author;
        private String body;
        private Long createdAt;
    }
}
