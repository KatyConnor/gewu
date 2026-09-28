package com.veloflow.engine.definition;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.veloflow.engine.definition.dto.*;
import com.veloflow.engine.identity.FlowIdentityProvider;
import com.veloflow.engine.commons.FlowPage;
import com.veloflow.engine.commons.VeloflowErrorCode;
import com.veloflow.engine.commons.VeloflowException;
import com.veloflow.engine.commons.FlowPageResult;


import com.veloflow.engine.persistence.model.Workflow;
import com.veloflow.engine.persistence.model.WorkflowAuditLog;
import com.veloflow.engine.persistence.model.WorkflowInstance;
import com.veloflow.engine.persistence.model.WorkflowNode;
import com.veloflow.engine.persistence.model.WorkflowNodeInstance;
import com.veloflow.engine.persistence.model.WorkflowNotification;
import com.veloflow.engine.persistence.model.WorkflowTransition;
import com.veloflow.engine.persistence.mapper.WorkflowAuditLogMapper;
import com.veloflow.engine.persistence.mapper.WorkflowInstanceMapper;
import com.veloflow.engine.persistence.mapper.WorkflowMapper;
import com.veloflow.engine.persistence.mapper.WorkflowNodeInstanceMapper;
import com.veloflow.engine.persistence.mapper.WorkflowNodeMapper;
import com.veloflow.engine.persistence.mapper.WorkflowNotificationMapper;
import com.veloflow.engine.persistence.mapper.WorkflowTransitionMapper;
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
    private final FlowIdentityProvider identityProvider;
    private final com.veloflow.engine.runtime.WorkflowScheduler workflowScheduler;

    /**
     * 触发器启动入口（52 号 P3）：SCHEDULE/WEBHOOK/EVENT/UPSTREAM 触发时
     * 以系统或外部身份发起；按触发类型定位对应触发器节点（宽松回退任意触发器）。
     */
    public WorkflowInstanceDTO startByTrigger(String workflowId, String initiatorId, String title,
                                              String variables, String triggerType) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_NOT_FOUND);
        }
        if (workflow.getStatus() == null || workflow.getStatus() != 1) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE, "工作流未发布，不能发起实例");
        }
        String triggerNodeType = switch (triggerType) {
            case "SCHEDULE" -> "schedule-trigger";
            case "WEBHOOK" -> "webhook-trigger";
            case "EVENT" -> "event-trigger";
            case "UPSTREAM" -> "upstream-trigger";
            default -> "manual-trigger";
        };
        WorkflowNode triggerNode = workflowNodeMapper.selectOne(
                new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getWorkflowId, workflowId)
                        .eq(WorkflowNode::getNodeType, triggerNodeType)
                        .last("LIMIT 1"));
        if (triggerNode == null) {
            // 宽松回退：任意触发器节点
            triggerNode = workflowNodeMapper.selectOne(
                    new LambdaQueryWrapper<WorkflowNode>()
                            .eq(WorkflowNode::getWorkflowId, workflowId)
                            .in(WorkflowNode::getNodeType,
                                    "manual-trigger", "schedule-trigger", "webhook-trigger",
                                    "event-trigger", "upstream-trigger", "start")
                            .last("LIMIT 1"));
        }
        if (triggerNode == null) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_NODE_NOT_FOUND, "工作流缺少触发器节点");
        }
        WorkflowInstance instance = workflowScheduler.start(triggerNode, initiatorId,
                title != null ? title : triggerType + " 触发", parseVariables(variables), triggerType);
        writeAuditLog(workflowId, instance.getId(), triggerNode.getId(), "START", null, "running");
        return toInstanceDTO(instance);
    }

    /**
     * 启动工作流实例（P1 内核重构，51 号 §四）：
     * 仅已发布工作流可发起；调度器完成触发器激活与推进（支持并行/循环/自动节点）。
     */
    @Transactional
    public WorkflowInstanceDTO startInstance(String workflowId, StartInstanceCommand command) {
        Workflow workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_NOT_FOUND);
        }
        if (workflow.getStatus() == null || workflow.getStatus() != 1) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE, "工作流未发布，不能发起实例");
        }
        String initiatorId = requireCurrentUser();

        WorkflowNode triggerNode = workflowNodeMapper.selectOne(
                new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getWorkflowId, workflowId)
                        .in(WorkflowNode::getNodeType, "manual-trigger", "start")
                        .last("LIMIT 1"));
        if (triggerNode == null) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_NODE_NOT_FOUND, "工作流缺少触发器节点");
        }

        WorkflowInstance instance = workflowScheduler.start(triggerNode, initiatorId,
                command.getTitle(), parseVariables(command.getVariables()), "MANUAL");
        writeAuditLog(workflowId, instance.getId(), triggerNode.getId(), "START", null, "running");
        return toInstanceDTO(instance);
    }

    public WorkflowInstanceDTO getInstance(String instanceId) {
        return toInstanceDTO(getInstanceEntity(instanceId));
    }

    public FlowPageResult<WorkflowInstanceDTO> listInstances(String workflowId, FlowPage query) {
        // 手工分页（独立模块不依赖分页插件）
       
        LambdaQueryWrapper<WorkflowInstance> wrapper = new LambdaQueryWrapper<>();
        if (workflowId != null && !workflowId.isBlank()) {
            wrapper.eq(WorkflowInstance::getWorkflowId, workflowId);
        }
        wrapper.orderByDesc(WorkflowInstance::getCreatedAt);
        List<WorkflowInstance> all = workflowInstanceMapper.selectList(wrapper);
        long total = all.size();
        List<WorkflowInstance> records = all.stream()
                .skip((query.getPage() - 1) * query.getSize())
                .limit(query.getSize())
                .toList();
        return FlowPageResult.of(enrichInstances(records), total, query.getPage(), query.getSize());
    }

    public FlowPageResult<WorkflowInstanceDTO> listMyInstances(FlowPage query) {
        String userId = requireCurrentUser();
        // 手工分页（独立模块不依赖分页插件）
       
        List<WorkflowInstance> all = workflowInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getInitiatorId, userId)
                        .orderByDesc(WorkflowInstance::getCreatedAt));
        long total = all.size();
        List<WorkflowInstance> records = all.stream()
                .skip((query.getPage() - 1) * query.getSize())
                .limit(query.getSize())
                .toList();
        return FlowPageResult.of(enrichInstances(records), total, query.getPage(), query.getSize());
    }

    /**
     * 完成节点（人工动作，P1 重构）：按 nodeInstanceId 精确完成，推进交由调度器
     * （幂等：重复提交被状态机原子更新吞掉）。approved 语义并入输出 JSON 供表达式引用。
     */
    @Transactional
    public WorkflowNodeInstanceDTO completeNode(String instanceId, String nodeInstanceId,
                                                CompleteNodeCommand command) {
        WorkflowInstance instance = getInstanceEntity(instanceId);
        WorkflowNodeInstance nodeInstance = workflowNodeInstanceMapper.selectById(nodeInstanceId);
        if (nodeInstance == null || !instanceId.equals(nodeInstance.getInstanceId())) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_NODE_NOT_FOUND, "节点实例不存在或不属于该流程");
        }
        if (!"waiting".equals(nodeInstance.getStatus()) && !"running".equals(nodeInstance.getStatus())) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE,
                    "节点已完成/失败，无法重复完成（状态: " + nodeInstance.getStatus() + "）");
        }
        // 办理权限（51 号 §七）：assignee 精确指派仅本人可办；角色指派角色成员可办；
        // 未指派任意登录用户可办
        String me = requireCurrentUser();
        if (nodeInstance.getAssigneeId() != null && !nodeInstance.getAssigneeId().equals(me)) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_UNAUTHORIZED,
                    "该任务已指派给其他办理人，无权办理");
        }
        if (nodeInstance.getAssigneeId() == null && nodeInstance.getAssigneeRole() != null
                && !identityProvider.currentRoles().contains(nodeInstance.getAssigneeRole())) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_UNAUTHORIZED,
                    "该任务已指派给角色 " + nodeInstance.getAssigneeRole() + "，无权办理");
        }
        if (command.getRemark() != null && !command.getRemark().isBlank()) {
            nodeInstance.setRemark(command.getRemark());
            workflowNodeInstanceMapper.updateById(nodeInstance);
        }
        String outputJson = buildCompletionOutput(command);
        workflowScheduler.completeNodeExternally(instanceId, nodeInstanceId, true, outputJson);
        writeAuditLog(instance.getWorkflowId(), instanceId, nodeInstance.getNodeId(),
                command.getApproved() != null && !command.getApproved() ? "REJECT" : "COMPLETE",
                nodeInstance.getStatus(), "completed");
        WorkflowNodeInstance updated = workflowNodeInstanceMapper.selectById(nodeInstanceId);
        return toNodeInstanceDTO(updated != null ? updated : nodeInstance);
    }

    /**
     * 系统完成等待型节点（P3 二期）：事件交付/消息交付等无登录态链路，
     * 跳过办理权限校验（operator 由调用方声明，审计兜底 system）。
     */
    @Transactional
    public WorkflowNodeInstanceDTO completeNodeBySystem(String instanceId, String nodeInstanceId,
                                                        String outputJson, String operator) {
        return completeNodeBySystem(instanceId, nodeInstanceId, true, outputJson, operator);
    }

    /** 系统完成（可指定成败）：sub-workflow 子实例失败时父行走失败完成 */
    @Transactional
    public WorkflowNodeInstanceDTO completeNodeBySystem(String instanceId, String nodeInstanceId,
                                                        boolean success, String outputJson, String operator) {
        WorkflowInstance instance = getInstanceEntity(instanceId);
        WorkflowNodeInstance nodeInstance = workflowNodeInstanceMapper.selectById(nodeInstanceId);
        if (nodeInstance == null || !instanceId.equals(nodeInstance.getInstanceId())) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_NODE_NOT_FOUND, "节点实例不存在或不属于该流程");
        }
        if (!"waiting".equals(nodeInstance.getStatus()) && !"running".equals(nodeInstance.getStatus())) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE,
                    "节点已完成/失败，无法重复完成（状态: " + nodeInstance.getStatus() + "）");
        }
        workflowScheduler.completeNodeExternally(instanceId, nodeInstanceId, success, outputJson);
        writeAuditLog(instance.getWorkflowId(), instanceId, nodeInstance.getNodeId(),
                success ? "SYSTEM_COMPLETE" : "SYSTEM_FAIL", nodeInstance.getStatus(),
                success ? "completed" : "failed");
        WorkflowNodeInstance updated = workflowNodeInstanceMapper.selectById(nodeInstanceId);
        return toNodeInstanceDTO(updated != null ? updated : nodeInstance);
    }

    /**
     * 消息交付（53 号 §3.3 receive-message）：按实例/消息键定位等待中的
     * receive-message 节点并推进；payloadJson 为节点输出（JSON 文本）。
     */
    @Transactional
    public WorkflowNodeInstanceDTO deliverMessage(String instanceId, String messageKey,
                                                  String payloadJson) {
        LambdaQueryWrapper<WorkflowNodeInstance> query = new LambdaQueryWrapper<WorkflowNodeInstance>()
                .eq(WorkflowNodeInstance::getNodeType, "receive-message")
                .eq(WorkflowNodeInstance::getStatus, "waiting")
                .orderByAsc(WorkflowNodeInstance::getCreatedAt)
                .last("LIMIT 1");
        if (instanceId != null && !instanceId.isBlank()) {
            query.eq(WorkflowNodeInstance::getInstanceId, instanceId);
        }
        if (messageKey != null && !messageKey.isBlank()) {
            query.eq(WorkflowNodeInstance::getMessageKey, messageKey);
        }
        WorkflowNodeInstance row = workflowNodeInstanceMapper.selectOne(query);
        if (row == null) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_NODE_NOT_FOUND,
                    "无匹配的等待消息节点（instanceId=" + instanceId
                            + ", messageKey=" + messageKey + "）");
        }
        return completeNodeBySystem(row.getInstanceId(), row.getId(), payloadJson, "message");
    }

    /**
     * 我的待办（51 号 §七）：waiting 态且 assignee 命中当前用户
     * （精确指派=本人；角色指派=角色成员；未指派=全员可见）。
     */
    public FlowPageResult<WorkflowNodeInstanceDTO> myTodos(FlowPage query) {
        String me = requireCurrentUser();
        List<String> myRoles = identityProvider.currentRoles();
        List<WorkflowNodeInstance> all = workflowNodeInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeInstance>()
                        .eq(WorkflowNodeInstance::getStatus, "waiting")
                        .orderByAsc(WorkflowNodeInstance::getTimeoutAt));
        List<WorkflowNodeInstance> visible = all.stream()
                .filter(row -> row.getAssigneeId() == null && row.getAssigneeRole() == null
                        || me.equals(row.getAssigneeId())
                        || row.getAssigneeRole() != null && myRoles.contains(row.getAssigneeRole()))
                .toList();
        long total = visible.size();
        List<WorkflowNodeInstance> records = visible.stream()
                .skip((query.getPage() - 1) * query.getSize())
                .limit(query.getSize())
                .toList();
        return FlowPageResult.of(records.stream().map(this::toNodeInstanceDTO).toList(),
                total, query.getPage(), query.getSize());
    }

    /** 委托转办（51 号 §七）：当前办理人将任务转给他人，全程审计 */
    @Transactional
    public WorkflowNodeInstanceDTO delegate(String instanceId, String nodeInstanceId, String delegateTo) {
        if (delegateTo == null || delegateTo.isBlank()) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE, "被委托人不能为空");
        }
        String me = requireCurrentUser();
        WorkflowNodeInstance row = workflowNodeInstanceMapper.selectById(nodeInstanceId);
        if (row == null || !instanceId.equals(row.getInstanceId())
                || !"waiting".equals(row.getStatus())) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE,
                    "节点实例不存在或不在待办状态");
        }
        if (row.getAssigneeId() != null && !row.getAssigneeId().equals(me)) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_UNAUTHORIZED, "仅当前办理人可委托");
        }
        row.setAssigneeId(delegateTo);
        row.setAssigneeRole(null);
        row.setRemark((row.getRemark() == null ? "" : row.getRemark() + " | ") + "委托自 " + me);
        workflowNodeInstanceMapper.updateById(row);
        writeAuditLog(row.getInstanceId() != null ? instanceOfWorkflowId(instanceId) : null,
                instanceId, row.getNodeId(), "DELEGATE", me, delegateTo);
        return toNodeInstanceDTO(row);
    }

    private String instanceOfWorkflowId(String instanceId) {
        WorkflowInstance instance = workflowInstanceMapper.selectById(instanceId);
        return instance != null ? instance.getWorkflowId() : null;
    }

    /** 输出组装：command.output 优先；审批语义 approved 并入 JSON 输出（供下游表达式） */
    private String buildCompletionOutput(CompleteNodeCommand command) {
        Map<String, Object> output = new HashMap<>();
        if (command.getOutput() != null && !command.getOutput().isBlank()) {
            Map<String, Object> parsed = parseVariables(command.getOutput());
            if (!parsed.isEmpty()) {
                output.putAll(parsed);
            } else {
                output.put("output", command.getOutput());
            }
        }
        if (command.getApproved() != null) {
            output.put("approved", command.getApproved());
        }
        return output.isEmpty() ? "{}" : writeJson(output);
    }

    private String writeJson(Object value) {
        try {
            return com.veloflow.engine.commons.VeloflowJson.MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    @Transactional
    public void suspendInstance(String instanceId) {
        WorkflowInstance instance = getInstanceEntity(instanceId);
        if (!"running".equals(instance.getStatus())) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE, "仅运行中的实例可挂起");
        }
        instance.setStatus("suspended");
        workflowInstanceMapper.updateById(instance);
    }

    @Transactional
    public void resumeInstance(String instanceId) {
        WorkflowInstance instance = getInstanceEntity(instanceId);
        if (!"suspended".equals(instance.getStatus())) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INVALID_STATE, "仅挂起的实例可恢复");
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
        Map<String, String> userMap = batchUsers(assigneeIds);
        return nodes.stream().map(n -> toNodeInstanceDTO(n, userMap)).toList();
    }

    public FlowPageResult<WorkflowNotificationDTO> getMyNotifications(FlowPage query) {
        String userId = requireCurrentUser();
                List<WorkflowNotification> all = workflowNotificationMapper.selectList(
                new LambdaQueryWrapper<WorkflowNotification>()
                        .eq(WorkflowNotification::getRecipientId, userId)
                        .orderByDesc(WorkflowNotification::getSentAt));
        long total = all.size();
        List<WorkflowNotificationDTO> dtos = all.stream()
                .skip((query.getPage() - 1) * query.getSize())
                .limit(query.getSize())
                .map(this::toNotificationDTO)
                .toList();
        return FlowPageResult.of(dtos, total, query.getPage(), query.getSize());
    }

    @Transactional
    public void markNotificationRead(String notificationId) {
        WorkflowNotification notification = workflowNotificationMapper.selectById(notificationId);
        if (notification == null) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_UNAUTHORIZED, "通知不存在");
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
            Map<String, Object> config = com.veloflow.engine.commons.VeloflowJson.MAPPER.readValue(node.getConfig(), new TypeReference<>() {});
            Object assignee = config.get("assigneeId");
            return assignee == null ? null : String.valueOf(assignee);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> parseVariables(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return com.veloflow.engine.commons.VeloflowJson.MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private void sendNotification(String instanceId, String nodeInstanceId, String recipientId, String nodeName) {
        String recipient = recipientId != null ? recipientId : identityProvider.currentUserId();
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
        // 触发链路（webhook/定时）无登录态：兜底 system（52 号 P2 冒烟发现项）
        entry.setOperatorId(identityProvider.currentUserId() != null
                ? identityProvider.currentUserId() : "system");
        entry.setOperatorName(identityProvider.currentUsername() != null
                ? identityProvider.currentUsername() : "system");
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
        Map<String, String> userMap = batchUsers(userIds);
        Map<String, WorkflowNode> nodeMap = batchNodes(nodeIds);
        return instances.stream()
                .map(instance -> toInstanceDTO(instance, userMap, nodeMap))
                .toList();
    }

    private WorkflowInstanceDTO toInstanceDTO(WorkflowInstance instance) {
        Map<String, String> userMap = instance.getInitiatorId() != null
                ? identityProvider.batchUserNames(Set.of(instance.getInitiatorId())) : Map.of();
        WorkflowNode currentNode = instance.getCurrentNodeId() != null
                ? workflowNodeMapper.selectById(instance.getCurrentNodeId()) : null;
        return toInstanceDTO(instance, userMap,
                currentNode != null ? Map.of(instance.getCurrentNodeId(), currentNode) : Map.of());
    }

    private WorkflowInstanceDTO toInstanceDTO(WorkflowInstance instance,
                                               Map<String, String> userMap,
                                               Map<String, WorkflowNode> nodeMap) {
        String initiatorName = instance.getInitiatorId() != null ? userMap.get(instance.getInitiatorId()) : null;
        WorkflowNode currentNode = instance.getCurrentNodeId() != null ? nodeMap.get(instance.getCurrentNodeId()) : null;
        return WorkflowInstanceDTO.builder()
                .instanceId(instance.getId())
                .workflowId(instance.getWorkflowId())
                .workflowVersion(instance.getWorkflowVersion())
                .title(instance.getTitle())
                .status(instance.getStatus())
                .statusDesc(instanceStatusDesc(instance.getStatus()))
                .initiatorId(instance.getInitiatorId())
                .initiatorName(initiatorName != null ? initiatorName : null)
                .currentNodeId(instance.getCurrentNodeId())
                .currentNodeName(currentNode != null ? currentNode.getNodeName() : null)
                .variables(instance.getVariables())
                .finalOutput(instance.getFinalOutput())
                .respondPayload(instance.getRespondPayload())
                .errorMessage(instance.getErrorMessage())
                .triggerType(instance.getTriggerType())
                .startedAt(instance.getStartedAt())
                .completedAt(instance.getCompletedAt())
                .createdAt(instance.getCreatedAt())
                .build();
    }

    private WorkflowNodeInstanceDTO toNodeInstanceDTO(WorkflowNodeInstance nodeInstance) {
        Map<String, String> userMap = nodeInstance.getAssigneeId() != null
                ? identityProvider.batchUserNames(Set.of(nodeInstance.getAssigneeId())) : Map.of();
        return toNodeInstanceDTO(nodeInstance, userMap);
    }

    private WorkflowNodeInstanceDTO toNodeInstanceDTO(WorkflowNodeInstance nodeInstance,
                                                      Map<String, String> userMap) {
        String assigneeName = nodeInstance.getAssigneeId() != null ? userMap.get(nodeInstance.getAssigneeId()) : null;
        return WorkflowNodeInstanceDTO.builder()
                .nodeInstanceId(nodeInstance.getId())
                .instanceId(nodeInstance.getInstanceId())
                .nodeId(nodeInstance.getNodeId())
                .nodeName(nodeInstance.getNodeName())
                .nodeType(nodeInstance.getNodeType())
                .status(nodeInstance.getStatus())
                .statusDesc(nodeInstanceStatusDesc(nodeInstance.getStatus()))
                .assigneeId(nodeInstance.getAssigneeId())
                .assigneeName(assigneeName != null ? assigneeName : null)
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

    private Map<String, String> batchUsers(Set<String> userIds) {
        return identityProvider.batchUserNames(userIds);
    }

    private Map<String, WorkflowNode> batchNodes(Set<String> nodeIds) {
        if (nodeIds.isEmpty()) return Map.of();
        List<WorkflowNode> nodes = workflowNodeMapper.selectBatchIds(nodeIds);
        return nodes.stream().collect(Collectors.toMap(WorkflowNode::getId, n -> n));
    }

    private WorkflowInstance getInstanceEntity(String instanceId) {
        WorkflowInstance instance = workflowInstanceMapper.selectById(instanceId);
        if (instance == null) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_INSTANCE_NOT_FOUND);
        }
        return instance;
    }

    private String requireCurrentUser() {
        String userId = identityProvider.currentUserId();
        if (userId == null) {
            throw VeloflowException.of(VeloflowErrorCode.FLOW_UNAUTHORIZED);
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
