package com.gewu.domain.workflow;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_node_instance")
public class WorkflowNodeInstance extends BaseEntity {

    private String instanceId;
    private String nodeId;
    private String nodeName;
    private String nodeType;
    /** 分支键（并行分支序号/loop 迭代上下文） */
    private String branchKey;
    /** 循环迭代序号 */
    private Integer iteration;
    private String status;
    private String assigneeId;
    private String input;
    private String output;
    /** 重试次数 */
    private Integer retryCount;
    /** 失败原因 */
    private String errorMessage;
    /** 等待型节点超时到期时间 */
    private Long timeoutAt;
    private Long startedAt;
    private Long completedAt;
    private String remark;
}