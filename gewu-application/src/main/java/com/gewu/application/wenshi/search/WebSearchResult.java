package com.gewu.application.wenshi.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一次网络搜索的结果集。
 * <p>
 * 封装搜索查询、返回的结果条目列表及搜索耗时，
 * 由 {@link WebSearchService#search} 产出，传递给 {@link WebResultVerifier} 做正确性判断。
 *
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchResult {

    /** 搜索查询语句 */
    private String query;

    /** 搜索结果条目列表（未经正确性判断） */
    private List<WebSearchFragment> fragments;

    /** 搜索耗时（毫秒） */
    private long durationMs;

    /** 是否成功（false 表示搜索失败，fragments 为空） */
    private boolean success;

    /** 失败时的错误信息 */
    private String errorMessage;

    /** 搜索引擎返回的原始结果数（去重前） */
    private int totalResults;
}