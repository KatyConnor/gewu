package com.veloflow.engine.runtime.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.veloflow.engine.commons.VlfId;
import com.veloflow.engine.persistence.mapper.WorkflowNotificationMapper;
import com.veloflow.engine.persistence.mapper.WorkflowNodeInstanceMapper;
import com.veloflow.engine.persistence.model.WorkflowNotification;
import com.veloflow.engine.persistence.model.WorkflowNodeInstance;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批节点（51 号 §七，53 号 §3.2）：等待型多实例——
 * 激活时按审批人圈定展开审批人行（branch_key=approver-&lt;i&gt;，每审批人一行），
 * 聚合行（branch_key=''）挂起等待判定；审批人行完成由调度器 judgeApproval 判定：
 * <ul>
 *   <li>ANY 或签：任一通过即通过，全部驳回才驳回</li>
 *   <li>ALL 会签：全部通过才通过，任一驳回即驳回</li>
 *   <li>RATIO 按比例：通过数/总数 ≥ approveRatio 通过</li>
 * </ul>
 * 通过 → 聚合行完成推进；驳回 → 聚合行完成（approved=false）→ 调度器驳回回退原语。
 * 审批人行激活时向审批人发站内通知。
 */
@Component
@RequiredArgsConstructor
public class ApprovalHandler implements WorkflowNodeHandler {

    /** 审批人行 branch_key 前缀（调度器据此识别"审批人行完成"走判定而非推进） */
    public static final String ROW_BRANCH_PREFIX = "approver-";

    private final WorkflowNodeInstanceMapper nodeInstanceMapper;
    private final WorkflowNotificationMapper notificationMapper;

    @Override
    public String type() {
        return "approval";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.WAITING;
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        Map<String, Object> config = context.config();
        // 审批人圈定：approverIds[] 与 approverRole（角色成员运行期按待办查询匹配）
        List<String> approvers = new ArrayList<>();
        Object ids = config.get("approverIds");
        if (ids instanceof List<?> list) {
            list.forEach(id -> approvers.add(String.valueOf(id)));
        } else if (context.nodeInstance().getAssigneeId() != null) {
            approvers.add(context.nodeInstance().getAssigneeId());
        }
        String role = config.get("assigneeRole") != null ? String.valueOf(config.get("assigneeRole")) : null;
        if (approvers.isEmpty() && role == null) {
            // 未圈定：单行走"任意登录用户可办"（assignee_id 置空）
            approvers.add(null);
        }
        // 兜底行（调度器插入的聚合行）转为第一个审批人行语义：重置其分支键
        WorkflowNodeInstance aggregate = context.nodeInstance();
        aggregate.setBranchKey(approvers.size() > 1 ? rowBranch(0) : "");
        if (!approvers.isEmpty()) {
            aggregate.setAssigneeId(approvers.get(0));
        }
        aggregate.setAssigneeRole(role);

        // 其余审批人行（从第二个审批人开始补插）
        for (int i = 1; i < approvers.size(); i++) {
            WorkflowNodeInstance row = new WorkflowNodeInstance();
            row.setId(VlfId.next());
            row.setInstanceId(context.instance().getId());
            row.setNodeId(context.node().getId());
            row.setNodeName(context.node().getNodeName());
            row.setNodeType("approval");
            row.setBranchKey(rowBranch(i));
            row.setIteration(context.nodeInstance().getIteration() == null
                    ? 0 : context.nodeInstance().getIteration());
            row.setStatus("waiting");
            row.setAssigneeId(approvers.get(i));
            row.setAssigneeRole(role);
            row.setStartedAt(System.currentTimeMillis());
            nodeInstanceMapper.insert(row);
        }
        TaskHandler.applyTimeout(context, config);
        notifyApprovers(context, approvers, role);
    }

    /** 审批人行 branch 键 */
    public static String rowBranch(int index) {
        return ApprovalHandler.ROW_BRANCH_PREFIX + index;
    }

    /** 审批人行激活通知（REVIEW_REQUIRED） */
    private void notifyApprovers(WorkflowNodeContext context, List<String> approvers, String role) {
        String title = "审批请求: " + context.node().getNodeName();
        for (String approver : approvers) {
            insertNotification(context.instance().getId(), context.nodeInstance().getId(),
                    approver != null ? approver : role, title,
                    "您有新的审批任务待处理" + (role != null && approver == null ? "（角色 " + role + "）" : ""));
        }
        if (role != null) {
            // 角色圈定：另插一条角色待办通知（recipient 存角色编码，通知中心按角色过滤）
            insertNotification(context.instance().getId(), context.nodeInstance().getId(),
                    "role:" + role, title, "角色 " + role + " 有新的审批任务待处理");
        }
    }

    private void insertNotification(String instanceId, String nodeInstanceId,
                                    String recipient, String title, String content) {
        WorkflowNotification notification = new WorkflowNotification();
        notification.setId(VlfId.next());
        notification.setInstanceId(instanceId);
        notification.setNodeInstanceId(nodeInstanceId);
        notification.setType("REVIEW_REQUIRED");
        notification.setRecipientId(recipient);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setIsRead(0);
        notification.setSentAt(System.currentTimeMillis());
        notificationMapper.insert(notification);
    }
}
