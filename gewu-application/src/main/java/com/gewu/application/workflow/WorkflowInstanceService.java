package com.gewu.application.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gewu.application.workflow.dto.*;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.user.UserAccount;
import com.gewu.domain.workflow.Workflow;
import com.gewu.domain.workflow.WorkflowAuditLog;
import com.gewu.domain.workflow.WorkflowInstance;
import com.gewu.domain.workflow.WorkflowNode;
import com.gewu.domain.workflow.WorkflowNodeInstance;
import com.gewu.domain.workflow.WorkflowNotification;
import com.gewu.domain.workflow.WorkflowTransition;
import com.gewu.infrastructure.mapper.UserAccountMapper;
import com.gewu.infrastructure.mapper.WorkflowAuditLogMapper;
import com.gewu.infrastructure.mapper.WorkflowInstanceMapper;
import com.gewu.infrastructure.mapper.WorkflowMapper;
import com.gewu.infrastructure.mapper.WorkflowNodeInstanceMapper;
import com.gewu.infrastructure.mapper.WorkflowNodeMapper;
import com.gewu.infrastructure.mapper.WorkflowNotificationMapper;
import com.gewu.infrastructure.mapper.WorkflowTransitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作流实例应用服务 — 实例启动、节点流转、挂起/恢复/终止及通知审计.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowInstanceService {

    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final WorkflowNodeInstanceMapper workflowNodeInstanceMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final WorkflowMapper workflowMapper;
    private final WorkflowTransitionMapper workflowTransitionMapper;
    private final WorkflowNotificationMapper workflowNotificationMapper;
    private final WorkflowAuditLogMapper workflowAuditLogMapper;
    private final UserAccountMapper userAccountMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public WorkflowInstanceDTO startInstance(String workflowId, StartInstanceCommand command) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw BusinessException.of(ResultCode.WORKFLOW_NOT_FOUND);
        }
        String initiatorId = requireCurrentUser();

        WorkflowNode startNode = workflowNodeMapper.selectOne(
                new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getWorkflowId, workflowId)
                        .eq(WorkflowNode::getNodeType, "start"));
        if (startNode == null) {
            throw BusinessException.of(ResultCode.WORKFLOW_NODE_NOT_FOUND, "工作流缺少起始节点");
        }

        long now = Instant.now().toEpochMilli();
        WorkflowInstance instance = new WorkflowInstance();
        instance.setWorkflowId(workflowId);
        instance.setWorkflowVersion(workflow.getVersion());
        instance.setTitle(command.getTitle());
        instance.setStatus("running");
        instance.setInitiatorId(initiatorId);
        instance.setVariables(command.getVariables());
        instance.setStartedAt(now);
        workflowInstanceMapper.insert(instance);

        WorkflowNode nextNode = findNextNode(startNode.getId(), parseVariables(command.getVariables()));
        if (nextNode == null) {
            throw BusinessException.of(ResultCode.WORKFLOW_INVALID_STATE, "起始节点无后续流转");
        }

        if ("end".equals(nextNode.getNodeType())) {
            instance.setCurrentNodeId(nextNode.getId());
            instance.setStatus("completed");
            instance.setCompletedAt(now);
            workflowInstanceMapper.updateById(instance);
            writeAuditLog(workflowId, instance.getId(), nextNode.getId(), "START", null, "completed");
            return toInstanceDTO(instance);
        }

        WorkflowNodeInstance nodeInstance = createRunningNodeInstance(instance.getId(), nextNode, now);
        instance.setCurrentNodeId(nextNode.getId());
        workflowInstanceMapper.updateById(instance);

        sendNotification(instance.getId(), nodeInstance.getId(), nodeInstance.getAssigneeId(), nextNode.getNodeName());
        writeAuditLog(workflowId, instance.getId(), nextNode.getId(), "START", null, "running");
        return toInstanceDTO(instance);
    }

    public WorkflowInstanceDTO getInstance(String instanceId) {
        return toInstanceDTO(getInstanceEntity(instanceId));
    }

    public PageResult<WorkflowInstanceDTO> listInstances(String workflowId, PageQuery query) {
        Page<WorkflowInstance> page = new Page<>(query.getPage(), query.getSize());
        LambdaQueryWrapper<WorkflowInstance> wrapper = new LambdaQueryWrapper<>();
        if (workflowId != null && !workflowId.isBlank()) {
            wrapper.eq(WorkflowInstance::getWorkflowId, workflowId);
        }
        wrapper.orderByDesc(WorkflowInstance::getCreatedAt);
        Page<WorkflowInstance> result = workflowInstanceMapper.selectPage(page, wrapper);
        return PageResult.of(enrichInstances(result.getRecords()), result.getTotal(), query.getPage(), query.getSize());
    }

    public PageResult<WorkflowInstanceDTO> listMyInstances(PageQuery query) {
        String userId = requireCurrentUser();
        Page<WorkflowInstance> page = new Page<>(query.getPage(), query.getSize());
        Page<WorkflowInstance> result = workflowInstanceMapper.selectPage(page,
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getInitiatorId, userId)
                        .orderByDesc(WorkflowInstance::getCreatedAt));
        return PageResult.of(enrichInstances(result.getRecords()), result.getTotal(), query.getPage(), query.getSize());
    }

    @Transactional
    public WorkflowNodeInstanceDTO completeNode(String instanceId, CompleteNodeCommand command) {
        WorkflowInstance instance = getInstanceEntity(instanceId);
        if (!"running".equals(instance.getStatus())) {
            throw BusinessException.of(ResultCode.WORKFLOW_INVALID_STATE, "实例非运行中，无法完成节点");
        }

        List<WorkflowNodeInstance> runningNodes = workflowNodeInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeInstance>()
                        .eq(WorkflowNodeInstance::getInstanceId, instanceId)
                        .eq(WorkflowNodeInstance::getStatus, "running")
                        .orderByDesc(WorkflowNodeInstance::getCreatedAt));
        if (runningNodes.isEmpty()) {
            throw BusinessException.of(ResultCode.WORKFLOW_INVALID_STATE, "当前无运行中的节点");
        }
        WorkflowNodeInstance currentNodeInstance = runningNodes.get(0);
        long now = Instant.now().toEpochMilli();

        currentNodeInstance.setStatus("completed");
        currentNodeInstance.setOutput(command.getOutput());
        currentNodeInstance.setRemark(command.getRemark());
        currentNodeInstance.setCompletedAt(now);
        workflowNodeInstanceMapper.updateById(currentNodeInstance);

        Map<String, Object> variables = parseVariables(instance.getVariables());
        if (command.getApproved() != null) {
            variables.put("approved", command.getApproved());
        }
        WorkflowNode nextNode = findNextNode(currentNodeInstance.getNodeId(), variables);
        if (nextNode == null) {
            throw BusinessException.of(ResultCode.WORKFLOW_INVALID_STATE, "节点无后续流转");
        }

        if ("end".equals(nextNode.getNodeType())) {
            instance.setCurrentNodeId(nextNode.getId());
            instance.setStatus("completed");
            instance.setCompletedAt(now);
            workflowInstanceMapper.updateById(instance);
            writeAuditLog(instance.getWorkflowId(), instanceId, nextNode.getId(), "COMPLETE",
                    "running", "completed");
            return toNodeInstanceDTO(currentNodeInstance);
        }

        WorkflowNodeInstance nextNodeInstance = createRunningNodeInstance(instanceId, nextNode, now);
        instance.setCurrentNodeId(nextNode.getId());
        workflowInstanceMapper.updateById(instance);

        sendNotification(instanceId, nextNodeInstance.getId(), nextNodeInstance.getAssigneeId(), nextNode.getNodeName());
        writeAuditLog(instance.getWorkflowId(), instanceId, nextNode.getId(), "COMPLETE",
                "running", "running");
        return toNodeInstanceDTO(nextNodeInstance);
    }

    @Transactional
    public void suspendInstance(String instanceId) {
        WorkflowInstance instance = getInstanceEntity(instanceId);
        if (!"running".equals(instance.getStatus())) {
            throw BusinessException.of(ResultCode.WORKFLOW_INVALID_STATE, "仅运行中的实例可挂起");
        }
        instance.setStatus("suspended");
        workflowInstanceMapper.updateById(instance);
    }

    @Transactional
    public void resumeInstance(String instanceId) {
        WorkflowInstance instance = getInstanceEntity(instanceId);
        if (!"suspended".equals(instance.getStatus())) {
            throw BusinessException.of(ResultCode.WORKFLOW_INVALID_STATE, "仅挂起的实例可恢复");
        }
        instance.setStatus("running");
        workflowInstanceMapper.updateById(instance);
    }

    @Transactional
    public void terminateInstance(String instanceId) {
        WorkflowInstance instance = getInstanceEntity(instanceId);
        instance.setStatus("terminated");
        workflowInstanceMapper.updateById(instance);
        writeAuditLog(instance.getWorkflowId(), instanceId, instance.getCurrentNodeId(), "CANCEL",
                instance.getStatus(), "terminated");
    }

    public List<WorkflowNodeInstanceDTO> getInstanceNodes(String instanceId) {
        getInstanceEntity(instanceId);
        List<WorkflowNodeInstance> nodes = workflowNodeInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeInstance>()
                        .eq(WorkflowNodeInstance::getInstanceId, instanceId)
                        .orderByAsc(WorkflowNodeInstance::getCreatedAt));
        if (nodes.isEmpty()) {
            return List.of();
        }
        Set<String> assigneeIds = nodes.stream()
                .map(WorkflowNodeInstance::getAssigneeId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<String, UserAccount> userMap = batchUsers(assigneeIds);
        return nodes.stream().map(n -> toNodeInstanceDTO(n, userMap)).toList();
    }

    public PageResult<WorkflowNotificationDTO> getMyNotifications(PageQuery query) {
        String userId = requireCurrentUser();
        Page<WorkflowNotification> page = new Page<>(query.getPage(), query.getSize());
        Page<WorkflowNotification> result = workflowNotificationMapper.selectPage(page,
                new LambdaQueryWrapper<WorkflowNotification>()
                        .eq(WorkflowNotification::getRecipientId, userId)
                        .orderByDesc(WorkflowNotification::getSentAt));
        List<WorkflowNotificationDTO> dtos = result.getRecords().stream()
                .map(this::toNotificationDTO)
                .toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    @Transactional
    public void markNotificationRead(String notificationId) {
        WorkflowNotification notification = workflowNotificationMapper.selectById(notificationId);
        if (notification == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "通知不存在");
        }
        notification.setIsRead(1);
        workflowNotificationMapper.updateById(notification);
    }

    private WorkflowNodeInstance createRunningNodeInstance(String instanceId, WorkflowNode node, long now) {
        WorkflowNodeInstance nodeInstance = new WorkflowNodeInstance();
        nodeInstance.setInstanceId(instanceId);
        nodeInstance.setNodeId(node.getId());
        nodeInstance.setNodeName(node.getNodeName());
        nodeInstance.setNodeType(node.getNodeType());
        nodeInstance.setStatus("running");
        nodeInstance.setAssigneeId(resolveAssignee(node));
        nodeInstance.setStartedAt(now);
        workflowNodeInstanceMapper.insert(nodeInstance);
        return nodeInstance;
    }

    private WorkflowNode findNextNode(String fromNodeId, Map<String, Object> variables) {
        List<WorkflowTransition> transitions = workflowTransitionMapper.selectList(
                new LambdaQueryWrapper<WorkflowTransition>()
                        .eq(WorkflowTransition::getFromNodeId, fromNodeId)
                        .orderByAsc(WorkflowTransition::getSortOrder));
        if (transitions.isEmpty()) {
            return null;
        }
        String toNodeId = null;
        for (WorkflowTransition transition : transitions) {
            if (transition.getConditionExpr() != null && !transition.getConditionExpr().isBlank()) {
                if (evaluateCondition(transition.getConditionExpr(), variables)) {
                    toNodeId = transition.getToNodeId();
                    break;
                }
            }
        }
        if (toNodeId == null) {
            for (WorkflowTransition transition : transitions) {
                if (transition.getConditionExpr() == null || transition.getConditionExpr().isBlank()) {
                    toNodeId = transition.getToNodeId();
                    break;
                }
            }
        }
        if (toNodeId == null) {
            toNodeId = transitions.get(0).getToNodeId();
        }
        return workflowNodeMapper.selectById(toNodeId);
    }

    private boolean evaluateCondition(String conditionExpr, Map<String, Object> variables) {
        String expr = conditionExpr.trim();
        for (String op : new String[]{">=", "<=", "!=", "==", ">", "<"}) {
            int idx = expr.indexOf(op);
            if (idx > 0) {
                String key = expr.substring(0, idx).trim();
                String expected = expr.substring(idx + op.length()).trim();
                return compareValues(variables.get(key), expected, op);
            }
        }
        return true;
    }

    private boolean compareValues(Object actual, String expected, String op) {
        String actualStr = actual == null ? "" : String.valueOf(actual);
        if ("==".equals(op)) return actualStr.equals(expected);
        if ("!=".equals(op)) return !actualStr.equals(expected);
        try {
            double a = Double.parseDouble(actualStr);
            double e = Double.parseDouble(expected);
            return switch (op) {
                case ">" -> a > e;
                case "<" -> a < e;
                case ">=" -> a >= e;
                case "<=" -> a <= e;
                default -> false;
            };
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String resolveAssignee(WorkflowNode node) {
        if (node.getConfig() == null || node.getConfig().isBlank()) return null;
        try {
            Map<String, Object> config = objectMapper.readValue(node.getConfig(), new TypeReference<>() {});
            Object assignee = config.get("assigneeId");
            return assignee == null ? null : String.valueOf(assignee);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseVariables(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private void sendNotification(String instanceId, String nodeInstanceId, String recipientId, String nodeName) {
        String recipient = recipientId != null ? recipientId : UserContext.currentUserId();
        if (recipient == null) return;
        WorkflowNotification notification = new WorkflowNotification();
        notification.setInstanceId(instanceId);
        notification.setNodeInstanceId(nodeInstanceId);
        notification.setType("TASK_CREATED");
        notification.setRecipientId(recipient);
        notification.setTitle("新任务: " + nodeName);
        notification.setContent("您有新的工作流任务待处理");
        notification.setIsRead(0);
        notification.setSentAt(Instant.now().toEpochMilli());
        workflowNotificationMapper.insert(notification);
    }

    private void writeAuditLog(String workflowId, String instanceId, String nodeId,
                               String operation, String beforeState, String afterState) {
        WorkflowAuditLog entry = new WorkflowAuditLog();
        entry.setWorkflowId(workflowId);
        entry.setInstanceId(instanceId);
        entry.setNodeId(nodeId);
        entry.setOperation(operation);
        entry.setOperatorId(UserContext.currentUserId());
        entry.setOperatorName(UserContext.currentUsername());
        entry.setBeforeState(beforeState);
        entry.setAfterState(afterState);
        workflowAuditLogMapper.insert(entry);
    }

    private List<WorkflowInstanceDTO> enrichInstances(List<WorkflowInstance> instances) {
        if (instances.isEmpty()) {
            return List.of();
        }
        Set<String> userIds = instances.stream()
                .map(WorkflowInstance::getInitiatorId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Set<String> nodeIds = instances.stream()
                .map(WorkflowInstance::getCurrentNodeId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<String, UserAccount> userMap = batchUsers(userIds);
        Map<String, WorkflowNode> nodeMap = batchNodes(nodeIds);
        return instances.stream()
                .map(instance -> toInstanceDTO(instance, userMap, nodeMap))
                .toList();
    }

    private WorkflowInstanceDTO toInstanceDTO(WorkflowInstance instance) {
        UserAccount initiator = instance.getInitiatorId() != null
                ? userAccountMapper.selectById(instance.getInitiatorId()) : null;
        WorkflowNode currentNode = instance.getCurrentNodeId() != null
                ? workflowNodeMapper.selectById(instance.getCurrentNodeId()) : null;
        return toInstanceDTO(instance,
                initiator != null ? Map.of(instance.getInitiatorId(), initiator) : Map.of(),
                currentNode != null ? Map.of(instance.getCurrentNodeId(), currentNode) : Map.of());
    }

    private WorkflowInstanceDTO toInstanceDTO(WorkflowInstance instance,
                                               Map<String, UserAccount> userMap,
                                               Map<String, WorkflowNode> nodeMap) {
        UserAccount initiator = instance.getInitiatorId() != null ? userMap.get(instance.getInitiatorId()) : null;
        WorkflowNode currentNode = instance.getCurrentNodeId() != null ? nodeMap.get(instance.getCurrentNodeId()) : null;
        return WorkflowInstanceDTO.builder()
                .instanceId(instance.getId())
                .workflowId(instance.getWorkflowId())
                .workflowVersion(instance.getWorkflowVersion())
                .title(instance.getTitle())
                .status(instance.getStatus())
                .statusDesc(instanceStatusDesc(instance.getStatus()))
                .initiatorId(instance.getInitiatorId())
                .initiatorName(initiator != null ? initiator.getDisplayName() : null)
                .currentNodeId(instance.getCurrentNodeId())
                .currentNodeName(currentNode != null ? currentNode.getNodeName() : null)
                .variables(instance.getVariables())
                .startedAt(instance.getStartedAt())
                .completedAt(instance.getCompletedAt())
                .createdAt(instance.getCreatedAt())
                .build();
    }

    private WorkflowNodeInstanceDTO toNodeInstanceDTO(WorkflowNodeInstance nodeInstance) {
        UserAccount assignee = nodeInstance.getAssigneeId() != null
                ? userAccountMapper.selectById(nodeInstance.getAssigneeId()) : null;
        return toNodeInstanceDTO(nodeInstance,
                assignee != null ? Map.of(nodeInstance.getAssigneeId(), assignee) : Map.of());
    }

    private WorkflowNodeInstanceDTO toNodeInstanceDTO(WorkflowNodeInstance nodeInstance,
                                                      Map<String, UserAccount> userMap) {
        UserAccount assignee = nodeInstance.getAssigneeId() != null ? userMap.get(nodeInstance.getAssigneeId()) : null;
        return WorkflowNodeInstanceDTO.builder()
                .nodeInstanceId(nodeInstance.getId())
                .instanceId(nodeInstance.getInstanceId())
                .nodeId(nodeInstance.getNodeId())
                .nodeName(nodeInstance.getNodeName())
                .nodeType(nodeInstance.getNodeType())
                .status(nodeInstance.getStatus())
                .statusDesc(nodeInstanceStatusDesc(nodeInstance.getStatus()))
                .assigneeId(nodeInstance.getAssigneeId())
                .assigneeName(assignee != null ? assignee.getDisplayName() : null)
                .input(nodeInstance.getInput())
                .output(nodeInstance.getOutput())
                .startedAt(nodeInstance.getStartedAt())
                .completedAt(nodeInstance.getCompletedAt())
                .remark(nodeInstance.getRemark())
                .build();
    }

    private WorkflowNotificationDTO toNotificationDTO(WorkflowNotification notification) {
        return WorkflowNotificationDTO.builder()
                .notificationId(notification.getId())
                .instanceId(notification.getInstanceId())
                .nodeInstanceId(notification.getNodeInstanceId())
                .type(notification.getType())
                .recipientId(notification.getRecipientId())
                .title(notification.getTitle())
                .content(notification.getContent())
                .isRead(notification.getIsRead())
                .sentAt(notification.getSentAt())
                .build();
    }

    private Map<String, UserAccount> batchUsers(Set<String> userIds) {
        if (userIds.isEmpty()) return Map.of();
        List<UserAccount> users = userAccountMapper.selectBatchIds(userIds);
        return users.stream().collect(Collectors.toMap(UserAccount::getId, u -> u));
    }

    private Map<String, WorkflowNode> batchNodes(Set<String> nodeIds) {
        if (nodeIds.isEmpty()) return Map.of();
        List<WorkflowNode> nodes = workflowNodeMapper.selectBatchIds(nodeIds);
        return nodes.stream().collect(Collectors.toMap(WorkflowNode::getId, n -> n));
    }

    private WorkflowInstance getInstanceEntity(String instanceId) {
        WorkflowInstance instance = workflowInstanceMapper.selectById(instanceId);
        if (instance == null) {
            throw BusinessException.of(ResultCode.WORKFLOW_INSTANCE_NOT_FOUND);
        }
        return instance;
    }

    private String requireCurrentUser() {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private String instanceStatusDesc(String status) {
        if (status == null) return null;
        return switch (status) {
            case "running" -> "运行中";
            case "completed" -> "已完成";
            case "failed" -> "已失败";
            case "suspended" -> "已挂起";
            case "terminated" -> "已终止";
            default -> null;
        };
    }

    private String nodeInstanceStatusDesc(String status) {
        if (status == null) return null;
        return switch (status) {
            case "pending" -> "待处理";
            case "running" -> "处理中";
            case "completed" -> "已完成";
            case "failed" -> "已失败";
            case "skipped" -> "已跳过";
            default -> null;
        };
    }
}
