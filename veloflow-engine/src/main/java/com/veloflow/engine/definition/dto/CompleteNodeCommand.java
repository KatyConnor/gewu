package com.veloflow.engine.definition.dto;

import lombok.Data;

@Data
public class CompleteNodeCommand {

    private String output;

    private String remark;

    private Boolean approved;
}
