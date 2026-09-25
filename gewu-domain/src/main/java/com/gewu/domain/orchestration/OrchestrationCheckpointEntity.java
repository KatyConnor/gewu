package com.gewu.domain.orchestration;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 编排断点检查点 - 暂停生效时的持久化检查点（WFO-03）。
 * <p>进程重启后由恢复路径从本表重建检查点断点续跑；
 * 恢复成功即删（uk: execution_id），生命周期与暂停态等长。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("orchestration_checkpoint")
public class OrchestrationCheckpointEntity extends BaseEntity {

    /** 编排执行实例 ID（唯一） */
    private String executionId;
    /** 编排图 ID */
    private String graphId;
    /** 检查点图定义 JSON */
    private String graphSnapshot;
    /** 检查点变量快照 JSON */
    private String variables;
    /** 恢复起始节点 ID */
    private String resumeFromNode;
    /** 暂停时刻当前节点 ID */
    private String currentNodeId;
    /** 发起用户 ID */
    private String userId;
    /** 会话 ID */
    private String sessionId;
}
