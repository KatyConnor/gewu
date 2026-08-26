package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.agent.AgentExecutionCount;
import com.gewu.domain.session.Session;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SessionMapper extends BaseMapper<Session> {

    /**
     * 按 agent 统计会话数（对话次数）.
     * <p>使用原生 SQL 手动过滤 deleted=0，按 session.agent（Agent ID）分组计数.
     */
    @Select("<script>" +
            "SELECT agent AS agentId, COUNT(*) AS cnt FROM session WHERE deleted=0 AND agent IN " +
            "<foreach collection='agentIds' item='aid' open='(' separator=',' close=')'>#{aid}</foreach>" +
            " GROUP BY agent" +
            "</script>")
    List<AgentExecutionCount> countByAgentIds(@Param("agentIds") List<String> agentIds);

    /**
     * 原子递增会话消息计数并刷新最后消息时间（替代应用层读改写，消除并发覆盖）.
     */
    @Update("UPDATE session SET message_count = COALESCE(message_count, 0) + #{delta}, " +
            "last_message_at = #{lastMessageAt} WHERE id = #{sessionId} AND deleted = 0")
    int incrementMessageCount(@Param("sessionId") String sessionId,
                              @Param("delta") int delta,
                              @Param("lastMessageAt") long lastMessageAt);

    /**
     * 原子累计会话 token 用量与成本（T4.1 成本回填）.
     */
    @Update("UPDATE session SET tokens_input = COALESCE(tokens_input, 0) + #{inputTokens}, " +
            "tokens_output = COALESCE(tokens_output, 0) + #{outputTokens}, " +
            "tokens_reasoning = COALESCE(tokens_reasoning, 0) + #{reasoningTokens}, " +
            "cost = COALESCE(cost, 0) + #{cost} WHERE id = #{sessionId} AND deleted = 0")
    int appendUsage(@Param("sessionId") String sessionId,
                    @Param("inputTokens") int inputTokens,
                    @Param("outputTokens") int outputTokens,
                    @Param("reasoningTokens") int reasoningTokens,
                    @Param("cost") java.math.BigDecimal cost);
}
