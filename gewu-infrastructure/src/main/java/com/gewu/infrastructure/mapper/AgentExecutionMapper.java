package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.agent.AgentExecution;
import com.gewu.domain.agent.AgentExecutionCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentExecutionMapper extends BaseMapper<AgentExecution> {

    /**
     * 按 agent_id 统计执行次数.
     * <p>使用原生 SQL 绕过 MyBatis-Plus 逻辑删除（agent_execution 表无 deleted 列）.
     *
     * @param agentIds 要统计的 Agent ID 列表
     * @return 每个 Agent 的执行次数
     */
    @Select("<script>" +
            "SELECT agent_id, COUNT(*) AS cnt FROM agent_execution WHERE agent_id IN " +
            "<foreach collection='agentIds' item='aid' open='(' separator=',' close=')'>#{aid}</foreach>" +
            " GROUP BY agent_id" +
            "</script>")
    List<AgentExecutionCount> countByAgentIds(@Param("agentIds") List<String> agentIds);
}
