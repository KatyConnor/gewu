package com.gewu.application.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.agent.engine.orchestration.ExecutionControl;
import com.gewu.agent.engine.orchestration.OrchestrationCheckpointStore;
import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.orchestration.OrchestrationCheckpointEntity;
import com.gewu.infrastructure.mapper.OrchestrationCheckpointMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 检查点持久化实现（WFO-03，EXEPLAN-ORCH-2026-09）。
 * <p>把引擎的内存检查点（图 + 上下文 + 恢复节点）序列化落库
 * {@code orchestration_checkpoint}，使暂停检查点跨进程存活：
 * 暂停生效时双写，进程重启后恢复接口从本表重建断点续跑，恢复即删。
 * <p>容错语义：任何持久化失败仅告警（内存检查点仍有效，同进程恢复不受影响）；
 * 上下文的版本化状态版本号无法跨进程恢复，重建后从 0 重新计（仅影响后续
 * 驳回回滚的可回退深度，不影响断点续跑语义）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbOrchestrationCheckpointStore implements OrchestrationCheckpointStore {

    private final OrchestrationCheckpointMapper checkpointMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void save(String executionId, ExecutionControl.Checkpoint checkpoint) {
        OrchestrationCheckpointEntity entity = find(executionId).orElseGet(() -> {
            OrchestrationCheckpointEntity created = new OrchestrationCheckpointEntity();
            created.setId(Ulid.next());
            created.setExecutionId(executionId);
            return created;
        });
        entity.setGraphId(checkpoint.context().getGraphId() != null
                ? checkpoint.context().getGraphId()
                : checkpoint.graph().getGraphId());
        entity.setGraphSnapshot(writeJson(checkpoint.graph()));
        entity.setVariables(writeJson(checkpoint.context().snapshotVariables()));
        entity.setResumeFromNode(checkpoint.resumeFromNodeId());
        entity.setCurrentNodeId(checkpoint.context().getCurrentNodeId());
        entity.setUserId(checkpoint.context().getUserId());
        entity.setSessionId(checkpoint.context().getSessionId());
        if (entity.getCreatedAt() == null) {
            checkpointMapper.insert(entity);
        } else {
            checkpointMapper.updateById(entity);
        }
    }

    @Override
    public Optional<ExecutionControl.Checkpoint> load(String executionId) {
        OrchestrationCheckpointEntity entity = find(executionId).orElse(null);
        if (entity == null) {
            return Optional.empty();
        }
        try {
            OrchestrationGraph graph = objectMapper.readValue(entity.getGraphSnapshot(), OrchestrationGraph.class);
            Map<String, Object> variables = entity.getVariables() != null
                    ? objectMapper.readValue(entity.getVariables(), new TypeReference<Map<String, Object>>() { })
                    : Map.of();
            OrchestrationContext ctx = OrchestrationContext.builder()
                    .executionId(entity.getExecutionId())
                    .graphId(entity.getGraphId())
                    .userId(entity.getUserId())
                    .sessionId(entity.getSessionId())
                    .currentNodeId(entity.getCurrentNodeId())
                    .build();
            ctx.setVariables(variables);
            return Optional.of(new ExecutionControl.Checkpoint(graph, ctx, entity.getResumeFromNode()));
        } catch (Exception e) {
            log.error("检查点反序列化失败（按无检查点处理，需重新执行）: executionId={}", executionId, e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String executionId) {
        checkpointMapper.delete(new LambdaQueryWrapper<OrchestrationCheckpointEntity>()
                .eq(OrchestrationCheckpointEntity::getExecutionId, executionId));
    }

    @Override
    public boolean has(String executionId) {
        return find(executionId).isPresent();
    }

    private Optional<OrchestrationCheckpointEntity> find(String executionId) {
        return checkpointMapper.selectList(new LambdaQueryWrapper<OrchestrationCheckpointEntity>()
                        .eq(OrchestrationCheckpointEntity::getExecutionId, executionId))
                .stream().findFirst();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("检查点序列化失败: " + e.getMessage(), e);
        }
    }
}
