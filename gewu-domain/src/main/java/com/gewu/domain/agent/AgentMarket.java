package com.gewu.domain.agent;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 智能体广场上架快照实体.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_market", autoResultMap = true)
public class AgentMarket extends BaseEntity {

    private String agentId;
    private String agentName;
    private String description;
    private String modelProvider;
    private String modelName;
    private String modelConfig;
    private String systemPrompt;
    private String emoji;
    private String category;

    /** 标签数组，以 JSON 存储 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    private Integer stars;
    private Integer installCount;
    private String author;
    private Integer status;
    private Integer version;
}
