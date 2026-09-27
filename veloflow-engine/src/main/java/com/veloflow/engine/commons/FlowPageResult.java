package com.veloflow.engine.commons;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 分页结果 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlowPageResult<T> {

    private List<T> records;
    private long total;
    private long page;
    private long size;

    public static <T> FlowPageResult<T> of(List<T> records, long total, long page, long size) {
        return new FlowPageResult<>(records, total, page, size);
    }
}
