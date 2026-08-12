package com.gewu.application.requirement.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 需求统计 DTO.
 */
@Data
@Builder
public class RequirementStatsDTO {

    private Map<String, Long> statusCount;
    private Map<String, Long> typeCount;
    private Map<String, Long> priorityCount;
    private Long totalCount;
}
