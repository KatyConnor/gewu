package com.gewu.domain.evaluation;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评测记录 - LlmJudge 评估结果持久化实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("evaluation_record")
public class EvaluationRecordEntity extends BaseEntity {

    /** 关联执行实例 ID */
    private String executionId;
    /** 锚点用例 ID */
    private String caseId;
    /** 评估得分（0-10） */
    private Double score;
    /** 是否通过验收 */
    private Integer passed;
    /** 评估说明 */
    private String verdict;
    /** 评审模型供应商 */
    private String judgeProvider;
    /** 评审模型 */
    private String judgeModel;
}
