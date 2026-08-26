package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.infrastructure.dto.ExperimentGroupStats;
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

    /**
     * A/B 实验分组聚合统计（T3.4）：按 experiment_group 分组，
     * 左联 evaluation_record 取 LLM-as-Judge 平均分。
     *
     * @param from 开始时间（毫秒，null 不限）
     * @param to 结束时间（毫秒，null 不限）
     */
    @Select("<script>" +
            "SELECT e.experiment_group AS experimentGroup," +
            "  COUNT(*) AS totalCount," +
            "  SUM(CASE WHEN e.status = 'completed' THEN 1 ELSE 0 END) AS successCount," +
            "  AVG(e.duration_ms) AS avgDurationMs," +
            "  AVG(e.tokens_used) AS avgTokens," +
            "  AVG(j.score) AS avgJudgeScore," +
            "  COUNT(j.id) AS judgedCount " +
            "FROM agent_execution e " +
            "LEFT JOIN evaluation_record j ON j.execution_id = e.id " +
            "WHERE e.experiment_group IS NOT NULL " +
            "<if test='from != null'> AND e.started_at &gt;= #{from}</if>" +
            "<if test='to != null'> AND e.started_at &lt;= #{to}</if>" +
            " GROUP BY e.experiment_group ORDER BY e.experiment_group" +
            "</script>")
    List<ExperimentGroupStats> aggregateByExperimentGroup(@Param("from") Long from, @Param("to") Long to);
}
