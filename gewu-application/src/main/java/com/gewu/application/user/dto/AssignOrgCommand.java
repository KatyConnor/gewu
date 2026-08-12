package com.gewu.application.user.dto;

import lombok.Data;

/**
 * 分配用户机构命令.
 */
@Data
public class AssignOrgCommand {

    /** 机构ID（null 表示取消分配） */
    private String orgId;
}
