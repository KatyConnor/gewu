package com.gewu.application.session.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SessionDTO {

    private String sessionId;
    private String title;
    private Integer type;
    private String typeDesc;
    private String projectId;
    private Integer status;
    private String statusDesc;
    private Integer isPublic;
    /** 置顶标记：0 否 / 1 是 */
    private Integer pinned;
    /** 分享短链标识（仅分享开启时返回） */
    private String slug;
    /** 分享链接相对路径 */
    private String shareUrl;
    private Integer messageCount;
    private Long lastMessageAt;
    private String agent;
    private String directory;
    private Long createdAt;
    private String createdBy;
}
