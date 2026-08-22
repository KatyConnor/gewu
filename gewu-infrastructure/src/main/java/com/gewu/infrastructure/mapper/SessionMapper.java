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
}
