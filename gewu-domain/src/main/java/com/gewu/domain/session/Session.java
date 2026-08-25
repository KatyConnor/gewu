package com.gewu.domain.session;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("session")
public class Session extends BaseEntity {

    private String title;
    private Integer type;
    private String projectId;
    private Integer status;
    private Integer isPublic;
    /** 置顶标记：0 否 / 1 是（列表排序置顶优先） */
    private Integer pinned;
    private Long lastMessageAt;
    private Integer messageCount;
    private String parentId;
    private String agent;
    private String model;
    private String shareUrl;
    private String slug;
    private String directory;
    private String path;
    private String workspaceId;
    private String metadata;
    private BigDecimal cost;
    private Integer tokensInput;
    private Integer tokensOutput;
    private Integer tokensReasoning;
    private Integer tokensCacheRead;
    private Integer tokensCacheWrite;
    private Integer summaryAdditions;
    private Integer summaryDeletions;
    private Integer summaryFiles;
    private String summaryDiffs;
    private String revert;
    private String versionTag;
    private Long timeCompacting;
    private Long timeArchived;
}