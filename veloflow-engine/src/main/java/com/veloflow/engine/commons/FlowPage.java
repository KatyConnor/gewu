package com.veloflow.engine.commons;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 分页查询参数（页码从 1 起） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlowPage {

    private long page = 1;
    private long size = 20;
}
