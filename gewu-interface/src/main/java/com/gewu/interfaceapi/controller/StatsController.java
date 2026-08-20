package com.gewu.interfaceapi.controller;

import com.gewu.application.stats.StatsService;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计 API - 仪表盘与用量页数据聚合。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/stats")
@RequiredArgsConstructor
@Tag(name = "统计", description = "仪表盘与用量统计聚合")
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/dashboard")
    @Operation(summary = "仪表盘数据", description = "Agent 成功率/信任分布/执行统计/最近会话聚合")
    public Result<StatsService.DashboardStats> dashboard() {
        return Result.success(statsService.dashboard());
    }

    @GetMapping("/usage")
    @Operation(summary = "用量数据", description = "token 消耗/成本/状态分布/近期执行")
    public Result<StatsService.UsageStats> usage() {
        return Result.success(statsService.usage());
    }
}
