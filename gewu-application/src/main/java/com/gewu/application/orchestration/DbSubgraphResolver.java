package com.gewu.application.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.orchestration.SubgraphResolver;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.domain.orchestration.OrchestrationGraphEntity;
import com.gewu.domain.orchestration.OrchestrationGraphVersionEntity;
import com.gewu.infrastructure.mapper.OrchestrationGraphMapper;
import com.gewu.infrastructure.mapper.OrchestrationGraphVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 子图解析实现（WFO-04，EXEPLAN-ORCH-2026-09）。
 * <p>按 refId 加载可执行子图定义：仅接受 active 状态的编排图，
 * 优先返回最新版本快照（与执行取数语义一致），未命中回退草稿列。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbSubgraphResolver implements SubgraphResolver {

    private final OrchestrationGraphMapper graphMapper;
    private final OrchestrationGraphVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    @Override
    public OrchestrationGraph resolve(String graphId) throws Exception {
        OrchestrationGraphEntity entity = graphMapper.selectById(graphId);
        if (entity == null || !"active".equals(entity.getStatus())) {
            return null;
        }
        String definition = latestVersionSnapshot(graphId);
        if (definition == null || definition.isBlank()) {
            definition = entity.getGraphDefinition();
        }
        return objectMapper.readValue(definition, OrchestrationGraph.class);
    }

    private String latestVersionSnapshot(String graphId) {
        List<OrchestrationGraphVersionEntity> versions = versionMapper.selectList(
                new LambdaQueryWrapper<OrchestrationGraphVersionEntity>()
                        .eq(OrchestrationGraphVersionEntity::getGraphId, graphId)
                        .orderByDesc(OrchestrationGraphVersionEntity::getVersion)
                        .last("LIMIT 1"));
        return versions.stream().findFirst().map(OrchestrationGraphVersionEntity::getGraphDefinition).orElse(null);
    }
}
