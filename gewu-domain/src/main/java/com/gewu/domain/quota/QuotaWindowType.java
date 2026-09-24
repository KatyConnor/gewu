package com.gewu.domain.quota;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 配额窗口类型：5 小时为滚动窗口，周/月/季为自然对齐（周一起/月初/季初）。
 *
 * @since 1.0.0
 */
public enum QuotaWindowType {

    FIVE_HOUR, WEEK, MONTH, QUARTER;

    /** 计算当前窗口起点（毫秒）。 */
    public long windowStartMillis(long nowMillis) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone);
        return switch (this) {
            case FIVE_HOUR -> nowMillis - Duration.ofHours(5).toMillis();
            case WEEK -> today.with(DayOfWeek.MONDAY).atStartOfDay(zone).toInstant().toEpochMilli();
            case MONTH -> today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli();
            case QUARTER -> {
                int quarterStartMonth = (today.getMonthValue() - 1) / 3 * 3 + 1;
                yield today.withMonth(quarterStartMonth).withDayOfMonth(1)
                        .atStartOfDay(zone).toInstant().toEpochMilli();
            }
        };
    }

    /** 解析字符串（容错大写/小写），未知返回 null。 */
    public static QuotaWindowType parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
