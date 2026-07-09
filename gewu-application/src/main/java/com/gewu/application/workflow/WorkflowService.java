package com.gewu.application.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.application.workflow.dto.*;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.workflow.Workflow;
import com.gewu.domain.workflow.WorkflowInstance;
import com.gewu.domain.workflow.WorkflowNode;
import com.gewu.domain.workflow.WorkflowTransition;
import com.gewu.infrastructure.mapper.WorkflowInstanceMapper;
import com.gewu.infrastructure.mapper.WorkflowMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import com.gewu.infrastructure.mapper.WorkflowNodeMapper;
import com.gewu.infrastructure.mapper.WorkflowTransitionMapper;
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

    public PageResult<WorkflowDTO> listWorkflows(PageQuery query) {
        Page<Workflow> page = new Page<>(query.getPage(), query.getSize());
        Page<Workflow> result = workflowMapper.selectPage(page, new LambdaQueryWrapper<>());
        List<WorkflowDTO> dtos = result.getRecords().stream()
                .map(this::toDTO)
                .toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    @Transactional
    public WorkflowDTO updateWorkflow(String workflowId, UpdateWorkflowCommand command) {
        Workflow workflow = getWorkflowEntity(workflowId);
        if (workflow.getStatus() != 0) {
            throw BusinessException.of(ResultCode.WORKFLOW_INVALID_STATE, "仅草稿状态的工作流可编辑");
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
            throw BusinessException.of(ResultCode.WORKFLOW_INVALID_STATE, "存在运行中的实例，无法删除");
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
            throw BusinessException.of(ResultCode.WORKFLOW_NOT_FOUND);
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
