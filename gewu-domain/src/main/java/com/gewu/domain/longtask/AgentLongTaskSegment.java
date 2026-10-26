package com.gewu.domain.longtask;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 长任务分段执行记录（docs/design/45）：恢复的最小单位 + 审计载体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_long_task_segment")
public class AgentLongTaskSegment extends BaseEntity {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_INTERRUPTED = "interrupted";

    private String taskId;
    private Integer seq;
    private String status;
    /** 本段种子提示（目标+检查点摘要+审核反馈） */
    private String seedPrompt;
    /** 段结束摘要 */
    private String summary;
    /** 检查点 JSON（进度/计划/文件清单/下一步） */
    private String checkpoint;
    /** 目标评估结果 JSON */
    private String goalEval;
    private Long tokens;
    private String errorMsg;
    private Long startedAt;
    private Long finishedAt;
}
