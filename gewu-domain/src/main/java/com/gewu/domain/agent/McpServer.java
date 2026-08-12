package com.gewu.domain.agent;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mcp_server")
public class McpServer extends BaseEntity {

    private String name;
    private String description;
    private String transport;
    private String command;
    private String args;
    private String url;
    private String env;
    private Integer status;
}
