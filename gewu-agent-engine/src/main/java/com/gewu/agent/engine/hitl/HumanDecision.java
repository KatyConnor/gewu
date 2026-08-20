package com.gewu.agent.engine.hitl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人工决策结果。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HumanDecision {
    /** 决策：APPROVED / REJECTED / INPUT_VALUE / SELECTED / EDITED */
    private String decision;
    /** 决策内容（输入文本 / 选择项 / 修改后产物） */
    private String value;
    /** 操作人 */
    private String operatorId;
}