package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.usage.UsageLedger;
import com.gewu.domain.usage.UsageLedgerDayRow;
import com.gewu.domain.usage.UsageMessageDayRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * UsageLedger Mapper：流水 CRUD + 窗口聚合 + 统计聚合（SQL GROUP BY，dayIdx=epoch 天序号）。
 *
 * @since 1.0.0
 */
@Mapper
public interface UsageLedgerMapper extends BaseMapper<UsageLedger> {

    /** 窗口内 token 消耗合计（配额预检用）。 */
    @Select("SELECT COALESCE(SUM(total_tokens), 0) FROM usage_ledger "
            + "WHERE deleted = 0 AND user_id = #{userId} "
            + "AND created_at >= #{fromMs} AND created_at <= #{toMs}")
    long sumTokensByUserAndRange(@Param("userId") String userId,
                                 @Param("fromMs") long fromMs,
                                 @Param("toMs") long toMs);

    /**
     * 按模型×天聚合用量（统计用）。userId 为 NULL 时统计全量（管理员视角）。
     * dayIdx = FLOOR(created_at / 86400000)，时间桶在 Java 侧按粒度归并。
     */
    @Select("SELECT model_id AS modelId, FLOOR(created_at / 86400000) AS dayIdx, "
            + "COALESCE(SUM(input_tokens), 0) AS inputTokens, "
            + "COALESCE(SUM(output_tokens), 0) AS outputTokens, "
            + "COALESCE(SUM(reasoning_tokens), 0) AS reasoningTokens, "
            + "COALESCE(SUM(total_tokens), 0) AS totalTokens, "
            + "COALESCE(SUM(cost), 0) AS cost "
            + "FROM usage_ledger "
            + "WHERE deleted = 0 AND created_at >= #{fromMs} AND created_at <= #{toMs} "
            + "AND (#{userId} IS NULL OR user_id = #{userId}) "
            + "GROUP BY model_id, dayIdx")
    List<UsageLedgerDayRow> selectDailyUsageByModel(@Param("userId") String userId,
                                                    @Param("fromMs") long fromMs,
                                                    @Param("toMs") long toMs);

    /**
     * 按天聚合消息数（用户发送 / 智能体发送）。userId 为 NULL 时统计全量。
     * 数据源：session_message JOIN session（消息表无 user_id 列，归属取会话创建者）。
     */
    @Select("SELECT FLOOR(sm.created_at / 86400000) AS dayIdx, "
            + "COALESCE(SUM(CASE WHEN sm.message_type = 'user' THEN 1 ELSE 0 END), 0) AS userMessages, "
            + "COALESCE(SUM(CASE WHEN sm.message_type = 'assistant' THEN 1 ELSE 0 END), 0) AS agentMessages "
            + "FROM session_message sm INNER JOIN session s ON sm.session_id = s.id AND s.deleted = 0 "
            + "WHERE sm.deleted = 0 AND sm.created_at >= #{fromMs} AND sm.created_at <= #{toMs} "
            + "AND (#{userId} IS NULL OR s.created_by = #{userId}) "
            + "GROUP BY dayIdx")
    List<UsageMessageDayRow> selectDailyMessages(@Param("userId") String userId,
                                                 @Param("fromMs") long fromMs,
                                                 @Param("toMs") long toMs);
}
