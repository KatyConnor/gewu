package com.gewu.application.quota;

import com.gewu.application.quota.dto.QuotaPreflightResultDTO;
import com.gewu.application.quota.dto.QuotaWindowStatusDTO;
import com.gewu.application.quota.dto.UserPreferenceDTO;
import com.gewu.domain.quota.QuotaPlan;
import com.gewu.domain.quota.QuotaWindowType;
import com.gewu.domain.quota.QuotaPlanItem;
import com.gewu.infrastructure.mapper.UsageLedgerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户配额预检服务：按套餐窗口聚合 usage_ledger 消耗，产出熔断判定、剩余配额与提醒信号。
 * <p>窗口口径：FIVE_HOUR=滚动 5 小时；WEEK/MONTH/QUARTER=自然对齐（周一起/月初/季初）。
 * <p>提醒节流：每用户每窗口每阈值档（70/85/95%）在当前窗口期内只提醒一次
 * （内存标记，窗口切换后自然重置）。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserQuotaService {

    /** 提醒档位（利用率百分比，跨档提醒一次） */
    private static final int[] ALERT_LEVELS = {70, 85, 95};
    /** 节流标记的最大存活时间（毫秒）——超过即清扫，防止 Map 无限增长 */
    private static final long THROTTLE_MAX_TTL_MS = 24L * 60 * 60 * 1000;

    private final QuotaPlanService quotaPlanService;
    private final UserPreferenceService userPreferenceService;
    private final UsageLedgerMapper usageLedgerMapper;

    /** 节流标记：key = userId:windowType:level，value = 标记过期时间戳 */
    private final ConcurrentHashMap<String, Long> alertThrottle = new ConcurrentHashMap<>();

    /**
     * 配额预检。
     *
     * @return 未绑定套餐时 bound=false、无窗口限制（remainingTokens=null 表示不限）
     */
    public QuotaPreflightResultDTO preflight(String userId) {
        UserPreferenceDTO preference = userPreferenceService.get(userId);
        List<QuotaPlanItem> items = new ArrayList<>();
        QuotaPlan plan = quotaPlanService.findActivePlanWithItems(userId, items);
        if (plan == null || items.isEmpty()) {
            return QuotaPreflightResultDTO.builder()
                    .bound(false)
                    .blockEnabled(preference.getQuotaBlockEnabled())
                    .alertThreshold(preference.getQuotaAlertThreshold())
                    .windows(List.of())
                    .maxUtilization(0)
                    .shouldBlock(false)
                    .alertTriggered(false)
                    .remainingTokens(null)
                    .build();
        }

        long now = System.currentTimeMillis();
        List<QuotaWindowStatusDTO> windows = new ArrayList<>();
        long remainingMin = Long.MAX_VALUE;
        double maxUtil = 0;
        boolean crossedThreshold = false;
        for (QuotaPlanItem item : items) {
            QuotaWindowType type = QuotaWindowType.parse(item.getWindowType());
            if (type == null) {
                continue;
            }
            long windowStart = type.windowStartMillis(now);
            long used = usageLedgerMapper.sumTokensByUserAndRange(userId, windowStart, now);
            double util = item.getTokenLimit() > 0 ? (double) used / item.getTokenLimit() : 0;
            windows.add(QuotaWindowStatusDTO.builder()
                    .windowType(type.name())
                    .tokenLimit(item.getTokenLimit())
                    .usedTokens(used)
                    .utilization(util)
                    .build());
            maxUtil = Math.max(maxUtil, util);
            remainingMin = Math.min(remainingMin, Math.max(0, item.getTokenLimit() - used));
            if (isCrossingAlertLevel(userId, type, util, preference.getQuotaAlertThreshold(), now)) {
                crossedThreshold = true;
            }
        }

        boolean blockEnabled = Boolean.TRUE.equals(preference.getQuotaBlockEnabled());
        boolean shouldBlock = blockEnabled && maxUtil >= 1.0;
        return QuotaPreflightResultDTO.builder()
                .bound(true)
                .planName(plan.getPlanName())
                .windows(windows)
                .maxUtilization(maxUtil)
                .blockEnabled(blockEnabled)
                .shouldBlock(shouldBlock)
                .alertThreshold(preference.getQuotaAlertThreshold())
                .alertTriggered(crossedThreshold)
                .remainingTokens(remainingMin == Long.MAX_VALUE ? null : remainingMin)
                .build();
    }

    /** 跨档判定 + 节流标记（每窗口每档位在当前窗口期内只提醒一次）。 */
    private boolean isCrossingAlertLevel(String userId, QuotaWindowType type,
                                         double utilization, int alertThreshold, long now) {
        long windowStart = type.windowStartMillis(now);
        int percent = (int) Math.floor(utilization * 100);
        boolean crossed = false;
        for (int level : ALERT_LEVELS) {
            if (percent < level) {
                continue;
            }
            String key = userId + ":" + type.name() + ":" + level + ":" + windowStart;
            Long markedUntil = alertThrottle.get(key);
            if (markedUntil != null && markedUntil > now) {
                continue;
            }
            alertThrottle.put(key, now + THROTTLE_MAX_TTL_MS);
            crossed = true;
        }
        // 清扫过期标记（低频执行即可：每次调用顺带清理）
        if (alertThrottle.size() > 4096) {
            alertThrottle.entrySet().removeIf(e -> e.getValue() <= now);
        }
        // 用户配置的提醒阈值（如 80%）单独一路：跨过后提醒一次
        if (alertThreshold > 0 && percent >= alertThreshold) {
            String key = userId + ":" + type.name() + ":custom:" + alertThreshold + ":" + windowStart;
            Long markedUntil = alertThrottle.get(key);
            if (markedUntil == null || markedUntil <= now) {
                alertThrottle.put(key, now + THROTTLE_MAX_TTL_MS);
                crossed = true;
            }
        }
        return crossed;
    }
}
