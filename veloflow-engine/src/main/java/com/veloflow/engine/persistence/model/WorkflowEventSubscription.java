package com.veloflow.engine.persistence.model;

import com.baomidou.mybatisplus.annotation.TableName;
import com.veloflow.engine.commons.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Veloflow 事件订阅（53 号 §3.3 event-wait）：event-wait 节点挂起时按订阅事件类型
 * 各插一行（WAITING）；事件交付命中后本行 consumed、同组其余行取消。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("VLF_WORKFLOW_EVENT_SUBSCRIPTION")
public class WorkflowEventSubscription extends BaseEntity {

    /** 订阅来源: WAIT=事件等待节点 / TRIGGER=事件触发器节点 */
    private String subscriptionType;
    private String workflowId;
    /** WAIT 订阅所属实例；TRIGGER 订阅为 NULL */
    private String instanceId;
    private String nodeId;
    /** WAIT 订阅所属节点实例行；TRIGGER 订阅为 NULL */
    private String nodeInstanceId;
    private String eventType;
    /** waiting/consumed/cancelled */
    private String status;
}
