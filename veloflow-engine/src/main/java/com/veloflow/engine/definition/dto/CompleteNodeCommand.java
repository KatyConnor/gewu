package com.veloflow.engine.definition.dto;

import lombok.Data;

@Data
public class CompleteNodeCommand {

    private String output;

    private String remark;
    /** 审批/办理意见（与 remark 同义，兼容惯例字段名） */
    private String comment;

    private Boolean approved;
}
