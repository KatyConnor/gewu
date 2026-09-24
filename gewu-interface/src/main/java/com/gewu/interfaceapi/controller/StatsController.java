package com.gewu.interfaceapi.controller;

import com.gewu.application.stats.StatsService;
import com.gewu.application.stats.UsageStatsService;
import com.gewu.application.stats.dto.UsageDetailDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final UsageStatsService usageStatsService;

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

    @GetMapping("/usage-detail")
    @Operation(summary = "用量明细统计",
            description = "tokens/成本（总量+按模型分组与合计）与消息数（用户/智能体/合计），"
                    + "支持 day/week/month/quarter/year 粒度；普通用户仅本人数据，ADMIN 可传 all=true 看全局")
    public Result<UsageDetailDTO> usageDetail(
            @RequestParam(defaultValue = "day") String granularity,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "false") boolean all) {
        com.gewu.common.context.UserContext ctx = UserContext.get();
        boolean isAdmin = ctx != null && ctx.hasRole("ADMIN");
        boolean scopeAll = all && isAdmin;
        return Result.success(usageStatsService.usageDetail(scopeAll, granularity, days));
    }
}
