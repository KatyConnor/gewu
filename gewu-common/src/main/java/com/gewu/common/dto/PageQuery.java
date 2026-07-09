package com.gewu.common.dto;

import lombok.Data;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 分页查询参数.
 */
@Data
public class PageQuery {

    @Min(1)
    private int page = 1;

    @Min(1)
    @Max(100)
    private int size = 20;

    private String sortBy;
    private String sortOrder = "desc";

    public int getOffset() {
        return (page - 1) * size;
    }
}