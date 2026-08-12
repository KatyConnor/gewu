package com.gewu.application.requirement.dto;

import lombok.Data;

/**
 * 创建评论命令.
 */
@Data
public class CreateCommentCommand {

    private String content;
    private String parentId;
    private String attachments;
}
