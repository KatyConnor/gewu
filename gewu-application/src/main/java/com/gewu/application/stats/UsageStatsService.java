package com.gewu.application.stats;

import com.gewu.application.stats.dto.UsageDetailDTO;
import com.gewu.common.context.UserContext;
import com.gewu.domain.usage.UsageLedgerDayRow;
import com.gewu.domain.usage.UsageMessageDayRow;
import com.gewu.infrastructure.mapper.UsageLedgerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 用量明细统计服务：tokens/成本（按模型分组与合计）+ 消息数（用户/智能体/合计）。
 * <p>聚合策略：SQL 按「模型 × 天」/「天」归并（dayIdx=epoch 天），Java 侧按粒度
 * （day/week/month/quarter/year）归并时间桶并零填充——避免方言函数、保持单条 GROUP BY。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageStatsService {

    private static final int MAX_RANGE_DAYS = 366;

    private final UsageLedgerMapper usageLedgerMapper;

    /** 粒度解析（缺省 day）。 */
    public Granularity parseGranularity(String granularity) {
        if (granularity == null || granularity.isBlank()) {
            return Granularity.DAY;
        }
        try {
            return Granularity.valueOf(granularity.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Granularity.DAY;
        }
    }

    /**
     * 用量明细统计。
     *
     * @param scopeAll   true=全局（管理员）；false=仅当前用户
     * @param granularity day/week/month/quarter/year
     * @param days       统计范围（自然日数，含今天；上限 366）
     */
    public UsageDetailDTO usageDetail(boolean scopeAll, String granularity, int days) {
        Granularity gran = parseGranularity(granularity);
        int rangeDays = Math.max(1, Math.min(days, MAX_RANGE_DAYS));
        ZoneId zone = ZoneId.systemDefault();
        long to = System.currentTimeMillis();
        long from = LocalDate.ofInstant(Instant.ofEpochMilli(to), zone)
                .minusDays(rangeDays - 1L).atStartOfDay(zone).toInstant().toEpochMilli();
        String userId = scopeAll ? null : UserContext.currentUserId();

        // ---------- tokens/成本：模型×天 -> 粒度桶 ----------
        List<UsageLedgerDayRow> usageRows = usageLedgerMapper.selectDailyUsageByModel(userId, from, to);
        Map<String, Aggregator> buckets = new TreeMap<>();
        for (LocalDate d = LocalDate.ofInstant(Instant.ofEpochMilli(from), zone);
             !d.isAfter(LocalDate.ofInstant(Instant.ofEpochMilli(to), zone));
             d = d.plusDays(1)) {
            buckets.put(bucketKey(d, gran), new Aggregator());
        }
        Map<String, Aggregator> modelTotals = new LinkedHashMap<>();
        for (UsageLedgerDayRow row : usageRows) {
            LocalDate day = LocalDate.ofEpochDay(row.getDayIdx());
            String key = bucketKey(day, gran);
            Aggregator bucket = buckets.computeIfAbsent(key, k -> new Aggregator());
            bucket.add(row);
            modelTotals.computeIfAbsent(row.getModelId() == null ? "unknown" : row.getModelId(),
                    k -> new Aggregator()).add(row);
        }

        // ---------- 消息数：天 -> 粒度桶 ----------
        List<UsageMessageDayRow> messageRows = usageLedgerMapper.selectDailyMessages(userId, from, to);
        Map<String, long[]> messageBuckets = new TreeMap<>();
        for (LocalDate d = LocalDate.ofInstant(Instant.ofEpochMilli(from), zone);
             !d.isAfter(LocalDate.ofInstant(Instant.ofEpochMilli(to), zone));
             d = d.plusDays(1)) {
            messageBuckets.put(bucketKey(d, gran), new long[2]);
        }
        for (UsageMessageDayRow row : messageRows) {
            String key = bucketKey(LocalDate.ofEpochDay(row.getDayIdx()), gran);
            long[] acc = messageBuckets.computeIfAbsent(key, k -> new long[2]);
            acc[0] += row.getUserMessages() != null ? row.getUserMessages() : 0;
            acc[1] += row.getAgentMessages() != null ? row.getAgentMessages() : 0;
        }

        // ---------- 组装 ----------
        List<UsageDetailDTO.BucketUsage> tokenSeries = new ArrayList<>();
        List<UsageDetailDTO.BucketMessages> messageSeries = new ArrayList<>();
        long totalIn = 0, totalOut = 0, totalTokens = 0;
        double totalCost = 0;
        long totalUser = 0, totalAgent = 0;
        for (Map.Entry<String, Aggregator> e : buckets.entrySet()) {
            Aggregator agg = e.getValue();
            tokenSeries.add(UsageDetailDTO.BucketUsage.builder()
                    .bucket(e.getKey())
                    .inputTokens(agg.inputTokens)
                    .outputTokens(agg.outputTokens)
                    .totalTokens(agg.totalTokens)
                    .cost(agg.cost)
                    .models(agg.models.entrySet().stream()
                            .map(me -> UsageDetailDTO.ModelTotal.builder()
                                    .modelId(me.getKey())
                                    .inputTokens(me.getValue().inputTokens)
                                    .outputTokens(me.getValue().outputTokens)
                                    .totalTokens(me.getValue().totalTokens)
                                    .cost(me.getValue().cost)
                                    .build())
                            .sorted(Comparator.comparingLong(UsageDetailDTO.ModelTotal::getTotalTokens).reversed())
                            .toList())
                    .build());
            totalIn += agg.inputTokens;
            totalOut += agg.outputTokens;
            totalTokens += agg.totalTokens;
            totalCost += agg.cost;
        }
        for (Map.Entry<String, long[]> e : messageBuckets.entrySet()) {
            long user = e.getValue()[0];
            long agent = e.getValue()[1];
            messageSeries.add(UsageDetailDTO.BucketMessages.builder()
                    .bucket(e.getKey())
                    .userMessages(user)
                    .agentMessages(agent)
                    .total(user + agent)
                    .build());
            totalUser += user;
            totalAgent += agent;
        }

        List<UsageDetailDTO.ModelTotal> totals = modelTotals.entrySet().stream()
                .map(e -> UsageDetailDTO.ModelTotal.builder()
                        .modelId(e.getKey())
                        .inputTokens(e.getValue().inputTokens)
                        .outputTokens(e.getValue().outputTokens)
                        .totalTokens(e.getValue().totalTokens)
                        .cost(e.getValue().cost)
                        .build())
                .sorted(Comparator.comparingLong(UsageDetailDTO.ModelTotal::getTotalTokens).reversed())
                .toList();

        return UsageDetailDTO.builder()
                .granularity(gran.name().toLowerCase())
                .from(from)
                .to(to)
                .scope(userId == null ? "ALL" : "SELF")
                .tokensSeries(tokenSeries)
                .messageSeries(messageSeries)
                .modelTotals(totals)
                .totalInputTokens(totalIn)
                .totalOutputTokens(totalOut)
                .totalTokens(totalTokens)
                .totalCost(totalCost)
                .totalUserMessages(totalUser)
                .totalAgentMessages(totalAgent)
                .totalMessages(totalUser + totalAgent)
                .build();
    }

    /** 天 -> 粒度桶标签。 */
    private String bucketKey(LocalDate day, Granularity gran) {
        return switch (gran) {
            case DAY -> day.format(DateTimeFormatter.ISO_LOCAL_DATE);
            case WEEK -> day.with(WeekFields.ISO.dayOfWeek(), 1)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
            case MONTH -> day.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            case QUARTER -> "%d-Q%d".formatted(day.getYear(), (day.getMonthValue() - 1) / 3 + 1);
            case YEAR -> String.valueOf(day.getYear());
        };
    }

    enum Granularity { DAY, WEEK, MONTH, QUARTER, YEAR }

    /** 聚合累加器。 */
    private static final class Aggregator {
        private long inputTokens;
        private long outputTokens;
        private long totalTokens;
        private double cost;
        private final Map<String, Aggregator> models = new LinkedHashMap<>();

        void add(UsageLedgerDayRow row) {
            long in = row.getInputTokens() != null ? row.getInputTokens() : 0;
            long out = row.getOutputTokens() != null ? row.getOutputTokens() : 0;
            long total = row.getTotalTokens() != null ? row.getTotalTokens() : 0;
            double c = row.getCost() != null ? row.getCost().doubleValue() : 0;
            inputTokens += in;
            outputTokens += out;
            totalTokens += total;
            cost += c;
        }
    }
}
