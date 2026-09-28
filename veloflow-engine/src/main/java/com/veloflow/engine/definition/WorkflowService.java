package com.veloflow.engine.definition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.veloflow.engine.definition.dto.*;
import com.veloflow.engine.commons.FlowPage;
import com.veloflow.engine.commons.VeloflowErrorCode;
import com.veloflow.engine.commons.VeloflowException;
import com.veloflow.engine.commons.FlowPageResult;

import com.veloflow.engine.persistence.model.Workflow;
import com.veloflow.engine.persistence.model.WorkflowInstance;
import com.veloflow.engine.persistence.model.WorkflowNode;
import com.veloflow.engine.persistence.model.WorkflowTransition;
import com.veloflow.engine.persistence.mapper.WorkflowInstanceMapper;
import com.veloflow.engine.persistence.mapper.WorkflowMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import com.veloflow.engine.persistence.mapper.WorkflowNodeMapper;
import com.veloflow.engine.persistence.mapper.WorkflowTransitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流应用服务 — 流程定义的创建、查询、发布、归档及图编排.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final WorkflowTransitionMapper workflowTransitionMapper;
    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final com.veloflow.engine.persistence.mapper.WorkflowVersionMapper versionMapper;
    private final com.veloflow.engine.runtime.WorkflowDefinitionValidator definitionValidator;

    @Transactional
    public WorkflowDTO createWorkflow(CreateWorkflowCommand command) {
        Workflow workflow = new Workflow();
        workflow.setWorkflowName(command.getWorkflowName());
        workflow.setDescription(command.getDescription());
        workflow.setVersion(1);
        workflow.setStatus(0);
        workflow.setCategory(command.getCategory());
        workflow.setConfig(command.getConfig());
        workflowMapper.insert(workflow);
        return toDTO(workflow);
    }

    public WorkflowDTO getWorkflow(String workflowId) {
        return toDTO(getWorkflowEntity(workflowId));
    }

    public FlowPageResult<WorkflowDTO> listWorkflows(FlowPage query) {
        List<Workflow> all = workflowMapper.selectList(new LambdaQueryWrapper<>());
        long total = all.size();
        List<Workflow> records = all.stream()
                .skip((query.getPage() - 1) * query.getSize())
                .limit(query.getSize())
                .toList();
        List<WorkflowDTO> dtos = records.stream().map(this::toDTO).toList();
        return FlowPageResult.of(dtos, total, query.getPage(), query.getSize());
    }

    @Transactional
    public WorkflowDTO updateWorkflow(String workflowId, UpdateWorkflowCommand command) {
        Workflow workflow = getWorkflowEntity(workflowId);
        if (workflow.getStatus() != 0) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE, "仅草稿状态的工作流可编辑");
        }
        if (command.getWorkflowName() != null) workflow.setWorkflowName(command.getWorkflowName());
        if (command.getDescription() != null) workflow.setDescription(command.getDescription());
        if (command.getCategory() != null) workflow.setCategory(command.getCategory());
        if (command.getConfig() != null) workflow.setConfig(command.getConfig());
        workflowMapper.updateById(workflow);
        return toDTO(workflow);
    }

    @Transactional
    public void deleteWorkflow(String workflowId) {
        getWorkflowEntity(workflowId);
        Long runningCount = workflowInstanceMapper.selectCount(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getWorkflowId, workflowId)
                        .eq(WorkflowInstance::getStatus, "running"));
        if (runningCount > 0) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE, "存在运行中的实例，无法删除");
        }
        workflowNodeMapper.delete(new LambdaQueryWrapper<WorkflowNode>()
                .eq(WorkflowNode::getWorkflowId, workflowId));
        workflowTransitionMapper.delete(new LambdaQueryWrapper<WorkflowTransition>()
                .eq(WorkflowTransition::getWorkflowId, workflowId));
        workflowMapper.deleteById(workflowId);
    }

    @Transactional
    public WorkflowDTO publishWorkflow(String workflowId) {
        Workflow workflow = getWorkflowEntity(workflowId);
        // 发布闸（51 号 WV 双闸）：ERROR 级结构问题阻断发布
        runDefinitionValidation(workflowId);
        // 发布即快照（51 号 T4.4）：聚合当前图不可变存档，版本号同流程内自增
        long now = Instant.now().toEpochMilli();
        Integer lastVersion = versionMapper.selectList(
                        new LambdaQueryWrapper<com.veloflow.engine.persistence.model.WorkflowVersion>()
                                .eq(com.veloflow.engine.persistence.model.WorkflowVersion::getWorkflowId, workflowId)
                                .orderByDesc(com.veloflow.engine.persistence.model.WorkflowVersion::getVersion)
                                .last("LIMIT 1"))
                .stream().findFirst()
                .map(com.veloflow.engine.persistence.model.WorkflowVersion::getVersion)
                .orElse(0);
        int versionNumber = lastVersion + 1;
        com.veloflow.engine.persistence.model.WorkflowVersion snapshot =
                new com.veloflow.engine.persistence.model.WorkflowVersion();
        snapshot.setId(com.veloflow.engine.commons.VlfId.next());
        snapshot.setWorkflowId(workflowId);
        snapshot.setVersion(versionNumber);
        snapshot.setDefinitionSnapshot(buildSnapshotJson(workflowId));
        snapshot.setPublishedBy(workflow.getUpdatedBy());
        snapshot.setPublishedAt(now);
        versionMapper.insert(snapshot);
        workflow.setStatus(1);
        workflow.setWorkflowVersion(versionNumber);
        workflow.setPublishedAt(now);
        workflowMapper.updateById(workflow);
        log.info("工作流发布并快照: workflowId={}, version={}", workflowId, versionNumber);
        return toDTO(workflow);
    }

    /**
     * 聚合当前图为不可变快照 JSON（与 SaveWorkflowGraphCommand 同构、统一 biz_node_id
     * 坐标系——流转端点同步映射回 biz 身份，保证回滚写回 saveWorkflowGraph 时不因
     * 行 ID 重建而断桥，与 getWorkflowGraph 回显同构）。
     */
    private String buildSnapshotJson(String workflowId) {
        List<WorkflowNode> nodes = workflowNodeMapper.selectList(
                new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowNode::getSortOrder));
        Map<String, String> rowToBiz = new HashMap<>();
        for (WorkflowNode node : nodes) {
            rowToBiz.put(node.getId(), node.getBizNodeId() != null ? node.getBizNodeId() : node.getId());
        }
        List<WorkflowTransition> transitions = workflowTransitionMapper.selectList(
                new LambdaQueryWrapper<WorkflowTransition>()
                        .eq(WorkflowTransition::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTransition::getSortOrder));
        List<WorkflowTransitionDTO> transitionDTOs = transitions.stream()
                .map(t -> {
                    WorkflowTransitionDTO dto = toTransitionDTO(t);
                    dto.setFromNodeId(rowToBiz.getOrDefault(t.getFromNodeId(), t.getFromNodeId()));
                    dto.setToNodeId(rowToBiz.getOrDefault(t.getToNodeId(), t.getToNodeId()));
                    return dto;
                }).toList();
        List<WorkflowNodeDTO> nodeDTOs = nodes.stream().map(this::toNodeDTO).toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("nodes", nodeDTOs);
        snapshot.put("transitions", transitionDTOs);
        try {
            return com.veloflow.engine.commons.VeloflowJson.MAPPER.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE, "版本快照序列化失败: " + e.getMessage());
        }
    }

    /** 版本列表（新→旧） */
    public List<WorkflowVersionDTO> listVersions(String workflowId) {
        getWorkflowEntity(workflowId);
        return versionMapper.selectList(
                        new LambdaQueryWrapper<com.veloflow.engine.persistence.model.WorkflowVersion>()
                                .eq(com.veloflow.engine.persistence.model.WorkflowVersion::getWorkflowId, workflowId)
                                .orderByDesc(com.veloflow.engine.persistence.model.WorkflowVersion::getVersion))
                .stream().map(v -> WorkflowVersionDTO.builder()
                        .versionId(v.getId())
                        .workflowId(v.getWorkflowId())
                        .version(v.getVersion())
                        .publishedBy(v.getPublishedBy())
                        .publishedAt(v.getPublishedAt())
                        .build())
                .toList();
    }

    /** 版本快照详情（图结构，供设计器加载历史版本对比） */
    public SaveWorkflowGraphCommand getVersionSnapshot(String workflowId, int version) {
        getWorkflowEntity(workflowId);
        com.veloflow.engine.persistence.model.WorkflowVersion snapshot = versionMapper.selectOne(
                new LambdaQueryWrapper<com.veloflow.engine.persistence.model.WorkflowVersion>()
                        .eq(com.veloflow.engine.persistence.model.WorkflowVersion::getWorkflowId, workflowId)
                        .eq(com.veloflow.engine.persistence.model.WorkflowVersion::getVersion, version)
                        .last("LIMIT 1"));
        if (snapshot == null) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_NOT_FOUND, "版本快照不存在: v" + version);
        }
        return deserializeSnapshot(snapshot.getDefinitionSnapshot());
    }

    /**
     * 回滚到历史版本（51 号 T4.4）：存在运行中实例时拒绝（运行中实例继续按其
     * 绑定快照追溯）；快照内容写回活表、流程置回草稿——重新编辑后再次发布
     * 产生新版本号（内容=历史版本），版本链完整不覆盖。
     */
    @Transactional
    public WorkflowDTO rollbackToVersion(String workflowId, int version) {
        Workflow workflow = getWorkflowEntity(workflowId);
        Long active = workflowInstanceMapper.selectCount(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getWorkflowId, workflowId)
                        .in(WorkflowInstance::getStatus, "running", "waiting"));
        if (active != null && active > 0) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE,
                    "存在运行中实例（" + active + "），不可回滚；请先终止或等待实例结束");
        }
        SaveWorkflowGraphCommand snapshot = getVersionSnapshot(workflowId, version);
        workflow.setStatus(0);
        workflow.setPublishedAt(null);
        workflowMapper.updateById(workflow);
        saveWorkflowGraph(workflowId, snapshot);
        log.info("工作流回滚到版本: workflowId={}, version={}", workflowId, version);
        return toDTO(workflow);
    }

    @SuppressWarnings("unchecked")
    private SaveWorkflowGraphCommand deserializeSnapshot(String snapshotJson) {
        try {
            Map<String, Object> raw = com.veloflow.engine.commons.VeloflowJson.MAPPER.readValue(
                    snapshotJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            SaveWorkflowGraphCommand command = new SaveWorkflowGraphCommand();
            List<Map<String, Object>> rawNodes = (List<Map<String, Object>>) raw.get("nodes");
            List<Map<String, Object>> rawTransitions = (List<Map<String, Object>>) raw.get("transitions");
            com.fasterxml.jackson.databind.ObjectMapper mapper = com.veloflow.engine.commons.VeloflowJson.MAPPER;
            command.setNodes(rawNodes == null ? List.of() : rawNodes.stream()
                    .map(n -> mapper.convertValue(n, WorkflowNodeDTO.class)).toList());
            command.setTransitions(rawTransitions == null ? List.of() : rawTransitions.stream()
                    .map(t -> mapper.convertValue(t, WorkflowTransitionDTO.class)).toList());
            return command;
        } catch (VeloflowException e) {
            throw e;
        } catch (Exception e) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE, "版本快照解析失败: " + e.getMessage());
        }
    }

    @Transactional
    public WorkflowDTO archiveWorkflow(String workflowId) {
        Workflow workflow = getWorkflowEntity(workflowId);
        workflow.setStatus(2);
        workflowMapper.updateById(workflow);
        return toDTO(workflow);
    }

    public SaveWorkflowGraphCommand getWorkflowGraph(String workflowId) {
        getWorkflowEntity(workflowId);
        List<WorkflowNode> nodeEntities = workflowNodeMapper.selectList(
                new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowNode::getSortOrder));
        // 画布坐标系统一 biz_node_id（保存入参身份；V57 断桥修复的回显侧闭环），
        // 行 ID 仅在 biz 缺失（存量数据）时兜底；流转端点同步映射回 biz 身份
        Map<String, String> rowToBiz = new HashMap<>();
        for (WorkflowNode node : nodeEntities) {
            rowToBiz.put(node.getId(), node.getBizNodeId() != null ? node.getBizNodeId() : node.getId());
        }
        List<WorkflowNodeDTO> nodes = nodeEntities.stream().map(this::toNodeDTO).toList();
        List<WorkflowTransitionDTO> transitions = workflowTransitionMapper.selectList(
                        new LambdaQueryWrapper<WorkflowTransition>()
                                .eq(WorkflowTransition::getWorkflowId, workflowId)
                                .orderByAsc(WorkflowTransition::getSortOrder))
                .stream().map(t -> {
                    WorkflowTransitionDTO dto = toTransitionDTO(t);
                    dto.setFromNodeId(rowToBiz.getOrDefault(t.getFromNodeId(), t.getFromNodeId()));
                    dto.setToNodeId(rowToBiz.getOrDefault(t.getToNodeId(), t.getToNodeId()));
                    return dto;
                }).toList();
        SaveWorkflowGraphCommand graph = new SaveWorkflowGraphCommand();
        graph.setNodes(nodes);
        graph.setTransitions(transitions);
        return graph;
    }

    @Transactional
    public void saveWorkflowGraph(String workflowId, SaveWorkflowGraphCommand command) {
        Workflow target = getWorkflowEntity(workflowId);
        // 草稿保护（51 号 T4.4）：已发布流程改图会让运行中实例读到新结构——
        // 修改请回滚到草稿（版本回滚 API）或另建流程
        if (target.getStatus() == null || target.getStatus() != 0) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE,
                    "仅草稿状态可保存流程图（当前状态: " + target.getStatus() + "），"
                            + "已发布流程请先回滚为草稿");
        }
        workflowNodeMapper.delete(new LambdaQueryWrapper<WorkflowNode>()
                .eq(WorkflowNode::getWorkflowId, workflowId));
        workflowTransitionMapper.delete(new LambdaQueryWrapper<WorkflowTransition>()
                .eq(WorkflowTransition::getWorkflowId, workflowId));

        List<String> nodeIds = new ArrayList<>();
        Map<String, String> nodeIdMap = new HashMap<>();
        if (command.getNodes() != null) {
            for (WorkflowNodeDTO dto : command.getNodes()) {
                WorkflowNode node = new WorkflowNode();
                node.setWorkflowId(workflowId);
                node.setBizNodeId(dto.getNodeId());
                node.setNodeName(dto.getNodeName());
                node.setNodeType(dto.getNodeType());
                node.setConfig(dto.getConfig());
                node.setPositionX(dto.getPositionX());
                node.setPositionY(dto.getPositionY());
                node.setSortOrder(dto.getSortOrder());
                workflowNodeMapper.insert(node);
                nodeIdMap.put(dto.getNodeId() != null ? dto.getNodeId() : "", node.getId());
                nodeIds.add(node.getId());
                dto.setNodeId(node.getId());
            }
        }
        if (command.getTransitions() != null) {
            for (int i = 0; i < command.getTransitions().size(); i++) {
                WorkflowTransitionDTO dto = command.getTransitions().get(i);
                WorkflowTransition transition = new WorkflowTransition();
                transition.setWorkflowId(workflowId);
                String fromId = StringUtils.hasText(dto.getFromNodeId()) ? nodeIdMap.getOrDefault(dto.getFromNodeId(), dto.getFromNodeId()) : (i < nodeIds.size() ? nodeIds.get(i) : "");
                String toId = StringUtils.hasText(dto.getToNodeId()) ? nodeIdMap.getOrDefault(dto.getToNodeId(), dto.getToNodeId()) : (i + 1 < nodeIds.size() ? nodeIds.get(i + 1) : "");
                transition.setFromNodeId(fromId);
                transition.setToNodeId(toId);
                transition.setConditionExpr(dto.getConditionExpr());
                transition.setLabel(dto.getLabel());
                transition.setSortOrder(dto.getSortOrder());
                workflowTransitionMapper.insert(transition);
                dto.setTransitionId(transition.getId());
            }
        }
        runDefinitionValidation(workflowId);
    }

    /**
     * 保存闸（51 号 WV 双闸）：图保存后执行结构校验，ERROR 级回滚保存并抛可读错误。
     * （保存与校验同事务：校验失败抛异常触发回滚，节点/流转不落库）
     */
    private void runDefinitionValidation(String workflowId) {
        List<com.veloflow.engine.runtime.WorkflowDefinitionValidator.ValidationIssue> issues =
                definitionValidator.validate(workflowId);
        List<String> errors = issues.stream()
                .filter(com.veloflow.engine.runtime.WorkflowDefinitionValidator.ValidationIssue::isError)
                .map(issue -> issue.ruleId() + ": " + issue.message())
                .toList();
        if (!errors.isEmpty()) {
            throw VeloflowException.of(
                    VeloflowErrorCode.FLOW_INVALID_STATE,
                    "工作流结构校验未通过: " + String.join("; ", errors));
        }
        List<String> warnings = issues.stream()
                .filter(issue -> !issue.isError())
                .map(issue -> issue.ruleId() + ": " + issue.message())
                .toList();
        if (!warnings.isEmpty()) {
            log.warn("工作流结构警告: workflowId={}, warnings={}", workflowId, warnings);
        }
    }

    public List<WorkflowNodeDTO> getWorkflowNodes(String workflowId) {
        getWorkflowEntity(workflowId);
        return workflowNodeMapper.selectList(
                        new LambdaQueryWrapper<WorkflowNode>()
                                .eq(WorkflowNode::getWorkflowId, workflowId)
                                .orderByAsc(WorkflowNode::getSortOrder))
                .stream().map(this::toNodeDTO).toList();
    }

    private WorkflowDTO toDTO(Workflow workflow) {
        Long nodeCount = workflowNodeMapper.selectCount(
                new LambdaQueryWrapper<WorkflowNode>().eq(WorkflowNode::getWorkflowId, workflow.getId()));
        return WorkflowDTO.builder()
                .workflowId(workflow.getId())
                .workflowName(workflow.getWorkflowName())
                .description(workflow.getDescription())
                .version(workflow.getVersion())
                .workflowVersion(workflow.getWorkflowVersion())
                .status(workflow.getStatus())
                .statusDesc(workflowStatusDesc(workflow.getStatus()))
                .category(workflow.getCategory())
                .config(workflow.getConfig())
                .publishedAt(workflow.getPublishedAt())
                .createdAt(workflow.getCreatedAt())
                .createdBy(workflow.getCreatedBy())
                .nodeCount(nodeCount)
                .build();
    }

    private WorkflowNodeDTO toNodeDTO(WorkflowNode node) {
        return WorkflowNodeDTO.builder()
                .nodeId(node.getBizNodeId() != null ? node.getBizNodeId() : node.getId())
                .workflowId(node.getWorkflowId())
                .nodeName(node.getNodeName())
                .nodeType(node.getNodeType())
                .config(node.getConfig())
                .positionX(node.getPositionX())
                .positionY(node.getPositionY())
                .sortOrder(node.getSortOrder())
                .build();
    }

    private WorkflowTransitionDTO toTransitionDTO(WorkflowTransition transition) {
        return WorkflowTransitionDTO.builder()
                .transitionId(transition.getId())
                .workflowId(transition.getWorkflowId())
                .fromNodeId(transition.getFromNodeId())
                .toNodeId(transition.getToNodeId())
                .conditionExpr(transition.getConditionExpr())
                .label(transition.getLabel())
                .sortOrder(transition.getSortOrder())
                .build();
    }

    private Workflow getWorkflowEntity(String workflowId) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_NOT_FOUND);
        }
        return workflow;
    }

    private String workflowStatusDesc(Integer status) {
        if (status == null) return null;
        return switch (status) {
            case 0 -> "草稿";
            case 1 -> "已发布";
            case 2 -> "已归档";
            default -> null;
        };
    }
}
