package com.veloflow.engine.persistence.model;

import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.annotation.TableName;
import com.veloflow.engine.commons.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工作流实例持久化实体，对应表 VLF_WORKFLOW_INSTANCE，记录实例状态、发起人、触发方式与变量等运行时信息。
 *
 * @author 牛牛
 * @version 1.0.0
 * @since 2026-09-27
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("VLF_WORKFLOW_INSTANCE")
public class WorkflowInstance extends BaseEntity {

    private String workflowId;
    private Integer workflowVersion;
    /** 绑定的定义版本快照 ID */
    private String versionId;
    private String title;
    private String status;
    private String initiatorId;
    /** 触发方式: MANUAL/SCHEDULE/WEBHOOK/EVENT/UPSTREAM */
    private String triggerType;
    /** 终态输出（return 节点产出） */
    private String finalOutput;
    /** 同步响应载荷（respond 节点产出，webhook 触发链路同步返回） */
    private String respondPayload;
    /** 失败原因 */
    private String errorMessage;
    private String currentNodeId;
    private String variables;
    /** 乐观锁版本（P4-B 多副本前置：MP @Version + OptimisticLockerInnerInterceptor，跨副本并发守护） */
    @Version
    private Integer version;
    private Long startedAt;
    private Long completedAt;
}