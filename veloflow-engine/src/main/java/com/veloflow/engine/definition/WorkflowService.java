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
        workflow.setStatus(1);
        workflow.setPublishedAt(Instant.now().toEpochMilli());
        workflowMapper.updateById(workflow);
        return toDTO(workflow);
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
        List<WorkflowNodeDTO> nodes = workflowNodeMapper.selectList(
                        new LambdaQueryWrapper<WorkflowNode>()
                                .eq(WorkflowNode::getWorkflowId, workflowId)
                                .orderByAsc(WorkflowNode::getSortOrder))
                .stream().map(this::toNodeDTO).toList();
        List<WorkflowTransitionDTO> transitions = workflowTransitionMapper.selectList(
                        new LambdaQueryWrapper<WorkflowTransition>()
                                .eq(WorkflowTransition::getWorkflowId, workflowId)
                                .orderByAsc(WorkflowTransition::getSortOrder))
                .stream().map(this::toTransitionDTO).toList();
        SaveWorkflowGraphCommand graph = new SaveWorkflowGraphCommand();
        graph.setNodes(nodes);
        graph.setTransitions(transitions);
        return graph;
    }

    @Transactional
    public void saveWorkflowGraph(String workflowId, SaveWorkflowGraphCommand command) {
        getWorkflowEntity(workflowId);
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
                .nodeId(node.getId())
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
