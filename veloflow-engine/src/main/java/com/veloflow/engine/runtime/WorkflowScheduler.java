package com.veloflow.engine.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.veloflow.engine.runtime.handler.ConditionHandler;
import com.veloflow.engine.commons.VlfId;
import com.veloflow.engine.persistence.model.WorkflowInstance;
import com.veloflow.engine.persistence.model.WorkflowNode;
import com.veloflow.engine.persistence.model.WorkflowNodeInstance;
import com.veloflow.engine.persistence.model.WorkflowTransition;
import com.veloflow.engine.persistence.mapper.WorkflowInstanceMapper;
import com.veloflow.engine.persistence.mapper.WorkflowNodeInstanceMapper;
import com.veloflow.engine.persistence.mapper.WorkflowNodeMapper;
import com.veloflow.engine.persistence.mapper.WorkflowTransitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工作流调度器——持久化等待驱动内核（51 号 §四）。
 * <p>推进事件源：①人工动作（completeNode）②业务节点完成回调（Handler.complete）
 * ③定时器到期（WorkflowTimerRunner）。执行状态全在 DB，进程重启无损。
 * <p>控制流分工：结构性原语（parallel 扇出/join 汇聚/loop 迭代/return 终态）由调度器
 * 内置实现；业务节点（condition/switch/transform/http/delay/...）经
 * {@link WorkflowNodeHandlerRegistry} 分派 Handler。
 * <p>并发控制：onCompletion 按实例 JVM 锁串行化（防并行分支完成回调的变量竞态）；
 * 节点完成幂等（状态机原子更新吞掉重复回调）。多副本部署需升级分布式锁（51 号开放问题）。
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowScheduler {

    private final WorkflowInstanceMapper instanceMapper;
    private final WorkflowNodeMapper nodeMapper;
    private final WorkflowNodeInstanceMapper nodeInstanceMapper;
    private final WorkflowTransitionMapper transitionMapper;
    private final WorkflowNodeHandlerRegistry handlerRegistry;
    private final WorkflowExpressionEvaluator expressionEvaluator;

    /** 实例级推进锁（单实例部署；多副本时替换为分布式锁） */
    private final Map<String, ReentrantLock> instanceLocks = new ConcurrentHashMap<>();

    /** 运行时监听器（可选；trigger 层注册 webhook 同步返回/订阅清理/上游联动） */
    @Autowired(required = false)
    private List<WorkflowRuntimeListener> runtimeListeners = List.of();

    // ==================== 启动 ====================

    /**
     * 启动工作流实例：创建实例 → 激活触发器节点（AUTO，输出 trigger 变量）→ 推进。
     */
    @Transactional
    public WorkflowInstance start(WorkflowNode triggerNode, String initiatorId, String title,
                                  Map<String, Object> triggerVariables, String triggerType) {
        long now = System.currentTimeMillis();
        Map<String, Object> variables = new LinkedHashMap<>();
        if (triggerVariables != null) {
            variables.putAll(triggerVariables);
        }
        variables.put("trigger", triggerVariables != null ? triggerVariables : Map.of());

        WorkflowInstance instance = new WorkflowInstance();
        instance.setWorkflowId(triggerNode.getWorkflowId());
        instance.setWorkflowVersion(1);
        instance.setTitle(title);
        instance.setStatus("running");
        instance.setInitiatorId(initiatorId);
        instance.setTriggerType(triggerType != null ? triggerType : "MANUAL");
        instance.setVariables(writeJson(variables));
        instance.setStartedAt(now);
        instanceMapper.insert(instance);

        // 触发器节点：建行即完成（输出 trigger 变量快照），随后推进
        WorkflowNodeInstance triggerInstance = insertNodeInstance(instance.getId(), triggerNode, "", 0, now);
        String triggerOutput = writeJson(variables.get("trigger"));
        triggerInstance.setStatus("completed");
        triggerInstance.setOutput(jsonOrNull(triggerOutput));
        triggerInstance.setCompletedAt(now);
        nodeInstanceMapper.updateById(triggerInstance);
        // 触发器输出写入变量空间（下游节点 input/表达式引用，同节点输出约定）
        variables.put(varKey(triggerNode), triggerOutput);
        instance.setVariables(writeJson(variables));
        instanceMapper.updateById(instance);

        lock(instance.getId(), () ->
                advanceFrom(instance, triggerNode, "", 0, reloadVariables(instance.getId())));
        return instance;
    }

    // ==================== 节点激活与完成 ====================

    /** 激活节点（调度器内置控制流 + 注册表业务节点分派），branchKey/iteration 标记并行与迭代上下文 */
    private void activateNode(WorkflowInstance instance, WorkflowNode node,
                              String branchKey, int iteration, String upstreamOutput) {
        String type = node.getNodeType() == null ? "manual-trigger" : node.getNodeType();
        long now = System.currentTimeMillis();
        switch (type) {
            case "parallel" -> activateParallel(instance, node, now);
            case "join" -> log.warn("join 节点不应被直接激活（由到达计数驱动）: instanceId={}", instance.getId());
            case "return", "end" -> activateReturn(instance, node, branchKey, iteration, upstreamOutput, now);
            case "error-end" -> activateErrorEnd(instance, node, branchKey, iteration, now);
            case "terminate-end" -> activateTerminateEnd(instance, node, branchKey, iteration, now);
            default -> activateBusinessNode(instance, node, branchKey, iteration, upstreamOutput, now);
        }
    }

    /** 业务节点激活：建行（形态决定 running/waiting）→ 分派 Handler */
    private void activateBusinessNode(WorkflowInstance instance, WorkflowNode node,
                                      String branchKey, int iteration, String upstreamOutput, long now) {
        WorkflowNodeHandler handler = handlerRegistry.resolve(node.getNodeType());
        WorkflowNodeInstance nodeInstance = insertNodeInstance(instance.getId(), node, branchKey, iteration, now);
        nodeInstance.setStatus(handler.kind() == WorkflowNodeHandler.NodeKind.WAITING ? "waiting" : "running");
        nodeInstanceMapper.updateById(nodeInstance);

        instance.setCurrentNodeId(node.getId());
        instanceMapper.updateById(instance);

        Map<String, Object> variables = reloadVariables(instance.getId());
        Map<String, Object> mergedVars = new HashMap<>(variables);
        Map<String, Object> config = parseConfig(node.getConfig());
        // 上游输出作为本节点输入（串行链语义）；JSON 列空串非法，转 NULL
        nodeInstance.setInput(jsonOrNull(upstreamOutput));
        nodeInstanceMapper.updateById(nodeInstance);

        WorkflowNodeContext context = WorkflowNodeContext.of(instance, node, nodeInstance,
                Map.copyOf(mergedVars), config, upstreamOutput,
                completion -> onCompletion(instance.getId(), completion));
        try {
            handler.activate(context);
        } catch (Exception e) {
            // Handler 约定不抛异常；兜底转为失败完成（进入 onError 默认语义：实例 failed）
            log.error("节点处理器执行异常: instanceId={}, nodeType={}, nodeId={}",
                    instance.getId(), node.getNodeType(), node.getId(), e);
            onCompletion(instance.getId(),
                    new WorkflowNodeContext.Completion(nodeInstance.getId(), false, String.valueOf(e.getMessage())));
        } finally {
            // 仅对等待行落 Handler 登记的分派/超时字段（冒烟发现项修复：无条件整行
            // updateById 会用激活前的内存对象覆写 AUTO 节点同步完成已写入的终态）
            if ("waiting".equals(nodeInstance.getStatus())) {
                nodeInstanceMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeInstance>()
                        .eq(WorkflowNodeInstance::getId, nodeInstance.getId())
                        .eq(WorkflowNodeInstance::getStatus, "waiting")
                        .set(WorkflowNodeInstance::getBranchKey, nodeInstance.getBranchKey())
                        .set(WorkflowNodeInstance::getAssigneeId, nodeInstance.getAssigneeId())
                        .set(WorkflowNodeInstance::getAssigneeRole, nodeInstance.getAssigneeRole())
                        .set(WorkflowNodeInstance::getMessageKey, nodeInstance.getMessageKey())
                        .set(WorkflowNodeInstance::getTimeoutAt, nodeInstance.getTimeoutAt()));
            } else if (nodeInstance.getTimeoutAt() != null) {
                nodeInstanceMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeInstance>()
                        .eq(WorkflowNodeInstance::getId, nodeInstance.getId())
                        .set(WorkflowNodeInstance::getTimeoutAt, nodeInstance.getTimeoutAt()));
            }
        }
    }

    /**
     * 节点完成回调（幂等）：原子推进终态 → 写变量 → 推进出边。
     * 重复回调/已取消节点被状态机原子更新吞掉（affected=0 直接忽略）。
     */
    public void onCompletion(String instanceId, WorkflowNodeContext.Completion completion) {
        lock(instanceId, () -> {
            WorkflowNodeInstance nodeInstance = nodeInstanceMapper.selectById(completion.nodeInstanceId());
            if (nodeInstance == null) {
                return;
            }
            // 幂等闸：仅运行/等待态可完成
            int advanced = nodeInstanceMapper.update(null, new LambdaUpdateWrapper<WorkflowNodeInstance>()
                    .eq(WorkflowNodeInstance::getId, completion.nodeInstanceId())
                    .in(WorkflowNodeInstance::getStatus, "running", "waiting")
                    .set(WorkflowNodeInstance::getStatus, completion.success() ? "completed" : "failed")
                    .set(WorkflowNodeInstance::getOutput, jsonOrNull(completion.outputJson()))
                    .set(WorkflowNodeInstance::getErrorMessage, completion.success() ? null : completion.outputJson())
                    .set(WorkflowNodeInstance::getCompletedAt, System.currentTimeMillis()));
            if (advanced == 0) {
                return;
            }
            nodeInstance.setStatus(completion.success() ? "completed" : "failed");
            WorkflowInstance instance = instanceMapper.selectById(instanceId);
            if (instance == null) {
                return; // 实例已不存在（级联删除等）：仅留痕节点终态
            }
            if (instance == null || !"running".equals(instance.getStatus())) {
                return; // 实例已暂停/终止：节点终态已留痕，不再推进
            }
            if (!completion.success()) {
                // onError 语义 P1 默认 fail（51 号 §八 onError 三态中的默认；goto 错误分支列 P2）
                failInstance(instance, "节点 " + nodeInstance.getNodeName() + " 执行失败: "
                        + truncate(completion.outputJson()));
                return;
            }
            // 写变量：nodeId → 输出（同编排约定），供表达式与下游引用
            Map<String, Object> variables = reloadVariables(instance.getId());
            WorkflowNode completedNode = nodeMapper.selectById(nodeInstance.getNodeId());
            variables.put(varKey(completedNode), completion.outputJson());
            log.info("节点完成写变量: instanceId={}, varKey={}, keys={}",
                    instanceId, varKey(completedNode), variables.keySet());
            Object parsed = parseJson(completion.outputJson());
            if (parsed instanceof Map<?, ?> map) {
                map.forEach((k, v) -> variables.putIfAbsent(String.valueOf(k), v));
            }
            instance.setVariables(writeJson(variables));
            instanceMapper.updateById(instance);

            // 人工任务判定与驳回回退（52 号 P2，53 号 §3.2）
            String nodeType = nodeInstance.getNodeType() == null ? "" : nodeInstance.getNodeType();
            // 审批人行完成：走判定聚合（不直接推进），聚合判定通过/驳回后由 judgeApproval 驱动
            if ("approval".equals(nodeType) && nodeInstance.getBranchKey() != null
                    && nodeInstance.getBranchKey().startsWith("approver-")) {
                WorkflowNode approvalNode = nodeMapper.selectById(nodeInstance.getNodeId());
                if (approvalNode != null) {
                    judgeApproval(instance, approvalNode, nodeInstance, variables);
                    return;
                }
            }

            WorkflowNode node = completedNode;
            // respond 节点：payload 落实例列并通知同步返回链路（P3 二期，53 号 §3.3）
            if (node != null && "respond".equals(node.getNodeType())) {
                instanceMapper.update(null, new LambdaUpdateWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getId, instance.getId())
                        .set(WorkflowInstance::getRespondPayload, completion.outputJson()));
                runtimeListeners.forEach(l -> l.onRespond(instance.getId(), completion.outputJson()));
            }
            // event-wait 节点离开等待：清理该节点残留订阅（幂等）
            if (node != null && "event-wait".equals(node.getNodeType())) {
                runtimeListeners.forEach(l -> l.onEventWaitNodeSettled(
                        instance.getId(), nodeInstance.getId()));
            }
            if (node != null) {
                advanceFrom(instance, node, nodeInstance.getBranchKey(),
                        nodeInstance.getIteration() == null ? 0 : nodeInstance.getIteration(), variables);
            } else {
                checkBranchEnd(instance);
            }
        });
    }

    // ==================== 推进与路由 ====================

    /** 从已完成节点推进：求值出边 → 激活后继（join 计数 / loop 迭代 / 并行扇出 / 单目标路由） */
    private void advanceFrom(WorkflowInstance instance, WorkflowNode fromNode,
                             String branchKey, int iteration, Map<String, Object> variables) {
        List<WorkflowTransition> edges = outgoing(fromNode.getId());
        if (edges.isEmpty()) {
            checkBranchEnd(instance);
            return;
        }
        // loop 回边：目标为 loop 节点 → 迭代推进而非激活
        if (edges.size() == 1 && isLoopNode(edges.get(0).getToNodeId())) {
            WorkflowNode target = nodeMapper.selectById(edges.get(0).getToNodeId());
            if (target != null && "loop".equals(target.getNodeType())) {
                loopIterate(instance, target, branchKey, variables);
                return;
            }
        }
        // parallel 出边：全部扇出
        if ("parallel".equals(fromNode.getNodeType())) {
            fanOut(instance, fromNode, edges, variables);
            return;
        }
        // 常规路由：label 匹配（condition/switch 输出 matched）→ 条件求值 → 无条件边兜底
        WorkflowTransition selected = route(instance, fromNode, edges, variables);
        if (selected == null) {
            log.warn("节点无可命中出边: instanceId={}, nodeId={}", instance.getId(), fromNode.getId());
            failInstance(instance, "节点 " + fromNode.getNodeName() + " 无可命中出边");
            return;
        }
        WorkflowNode target = nodeMapper.selectById(selected.getToNodeId());
        if (target == null) {
            failInstance(instance, "出边指向不存在的节点: " + selected.getToNodeId());
            return;
        }
        if ("join".equals(target.getNodeType())) {
            joinArrive(instance, target, fromNode.getId(), branchKey, iteration,
                    String.valueOf(variables.getOrDefault(varKey(fromNode), "")));
            return;
        }
        String upstreamOutput = String.valueOf(variables.getOrDefault(varKey(fromNode), ""));
        activateNode(instance, target, branchKey, iteration, upstreamOutput);
    }

    /** 出边路由：fromNode 输出的 matched 标签（防跨节点覆盖）> 条件表达式 > 无条件边 */
    private WorkflowTransition route(WorkflowInstance instance, WorkflowNode fromNode,
                                     List<WorkflowTransition> edges, Map<String, Object> variables) {
        Object matched = null;
        Object fromOutput = parseJson(String.valueOf(variables.getOrDefault(varKey(fromNode), "")));
        if (fromOutput instanceof Map<?, ?> outputMap) {
            matched = outputMap.get("matched");
        }
        if (matched != null) {
            for (WorkflowTransition edge : edges) {
                if (matched.equals(edge.getLabel())) {
                    return edge;
                }
            }
        }
        for (WorkflowTransition edge : edges) {
            if (edge.getConditionExpr() != null && !edge.getConditionExpr().isBlank()
                    && expressionEvaluator.evaluateBoolean(edge.getConditionExpr(), variables)) {
                return edge;
            }
        }
        for (WorkflowTransition edge : edges) {
            if (edge.getLabel() == null || edge.getLabel().isBlank()
                    ? (edge.getConditionExpr() == null || edge.getConditionExpr().isBlank())
                    : "default".equalsIgnoreCase(edge.getLabel()) || "else".equalsIgnoreCase(edge.getLabel())) {
                return edge;
            }
        }
        return edges.size() == 1 ? edges.get(0) : null;
    }

    // ==================== 结构性原语 ====================

    /** parallel：建审计行（即完成）→ 登记各分支（branch_key=parallelId-i）→ 扇出全部出边 */
    private void activateParallel(WorkflowInstance instance, WorkflowNode node, long now) {
        WorkflowNodeInstance parallelRow = insertNodeInstance(instance.getId(), node, "", 0, now);
        parallelRow.setStatus("completed");
        parallelRow.setCompletedAt(now);
        nodeInstanceMapper.updateById(parallelRow);

        List<WorkflowTransition> edges = outgoing(node.getId());
        Map<String, Object> variables = reloadVariables(instance.getId());
        fanOut(instance, node, edges, variables);
    }

    private void fanOut(WorkflowInstance instance, WorkflowNode parallelNode,
                        List<WorkflowTransition> edges, Map<String, Object> variables) {
        for (int i = 0; i < edges.size(); i++) {
            WorkflowNode target = nodeMapper.selectById(edges.get(i).getToNodeId());
            if (target == null) {
                continue;
            }
            String branchKey = parallelNode.getId() + "-" + i;
            String upstream = String.valueOf(variables.getOrDefault(varKey(parallelNode), ""));
            if ("join".equals(target.getNodeType())) {
                joinArrive(instance, target, parallelNode.getId(), branchKey, 0,
                        String.valueOf(variables.getOrDefault(varKey(parallelNode), "")));
            } else {
                activateNode(instance, target, branchKey, 0, upstream);
            }
        }
    }

    /**
     * join 到达（行级记账，51 号 §四 join 三策略）：
     * 每个上游分支到达即插入一条 join 节点实例行（branch_key=前驱节点行 ID，
     * uk 四列防重复到达=幂等）；到达数=DB 实际行数（无缓存竞态）。
     * ALL 全部到达放行 / FIRST 首个放行（其余到忽略）/ N_OF_M 达 joinCount 放行；
     * 策略配置在 join 节点 config。无入边的 join 为非法图（WV 兜底）不推进。
     */
    private void joinArrive(WorkflowInstance instance, WorkflowNode joinNode, String fromNodeId,
                            String branchKey, int iteration, String upstreamOutput) {
        long now = System.currentTimeMillis();
        Map<String, Object> config = parseConfig(joinNode.getConfig());
        String strategy = String.valueOf(config.getOrDefault("joinStrategy", "ALL"));
        try {
            WorkflowNodeInstance arrivalRow = new WorkflowNodeInstance();
            arrivalRow.setId(VlfId.next());
            arrivalRow.setInstanceId(instance.getId());
            arrivalRow.setNodeId(joinNode.getId());
            arrivalRow.setNodeName(joinNode.getNodeName());
            arrivalRow.setNodeType("join");
            arrivalRow.setBranchKey(fromNodeId);
            arrivalRow.setIteration(0);
            arrivalRow.setStatus("completed");
            arrivalRow.setOutput(jsonOrNull(upstreamOutput));
            arrivalRow.setCompletedAt(now);
            arrivalRow.setStartedAt(now);
            nodeInstanceMapper.insert(arrivalRow);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return; // 重复到达：幂等忽略
        }
        long arrived = nodeInstanceMapper.selectCount(new LambdaQueryWrapper<WorkflowNodeInstance>()
                .eq(WorkflowNodeInstance::getInstanceId, instance.getId())
                .eq(WorkflowNodeInstance::getNodeId, joinNode.getId())
                .eq(WorkflowNodeInstance::getStatus, "completed"));
        int expect = transitionMapper.selectList(new LambdaQueryWrapper<WorkflowTransition>()
                .eq(WorkflowTransition::getToNodeId, joinNode.getId())).size();
        if (expect == 0) {
            return; // 无入边的 join 为非法图（WV 校验兜底）：不推进
        }
        boolean release = switch (strategy) {
            case "FIRST" -> arrived >= 1;
            case "N_OF_M" -> arrived >= Math.min(parseInt(config.get("joinCount"), expect), expect);
            default -> arrived >= expect; // ALL
        };
        log.info("join 到达记账: joinId={}, arrived={}/{}, strategy={}, release={}",
                joinNode.getId(), arrived, expect, strategy, release);
        if (!release) {
            return; // 未达策略阈值：等待其余分支
        }
        // 放行：聚合输出写入实例变量空间（return/下游表达式经节点键引用）
        Map<String, Object> joinedVars = reloadVariables(instance.getId());
        List<WorkflowNodeInstance> arrivalRows = nodeInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeInstance>()
                        .eq(WorkflowNodeInstance::getInstanceId, instance.getId())
                        .eq(WorkflowNodeInstance::getNodeId, joinNode.getId())
                        .eq(WorkflowNodeInstance::getStatus, "completed"));
        String merged = arrivalRows.stream()
                .map(r -> String.valueOf(r.getOutput() == null ? "" : r.getOutput()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        joinedVars.put(varKey(joinNode), merged);
        instance.setVariables(writeJson(joinedVars));
        instanceMapper.updateById(instance);
        // 未放行时在途的其他分支不再推进（FIRST/N_OF_M 语义）：后续到达被唯一键/计数吸收
        List<WorkflowTransition> edges = outgoing(joinNode.getId());
        if (edges.isEmpty()) {
            checkBranchEnd(instance);
            return;
        }
        WorkflowNode target = nodeMapper.selectById(edges.get(0).getToNodeId());
        if (target == null) {
            failInstance(instance, "join 出边指向不存在的节点");
            return;
        }
        if ("join".equals(target.getNodeType())) {
            joinArrive(instance, target, joinNode.getId(), branchKey, iteration, merged);
        } else {
            activateNode(instance, target, branchKey, iteration, merged);
        }
    }

    /**
     * loop 启动：求值数组 → 串行扇出（branch_key=loopId，iteration 递增）；
     * 循环体末节点出边指回 loop 即迭代完成（WV-04 对 loop 回边放行）。
     */
    private void loopIterate(WorkflowInstance instance, WorkflowNode loopNode,
                             String branchKey, Map<String, Object> variables) {
        Map<String, Object> vars = new LinkedHashMap<>(variables);
        variables = vars;
        String stateKey = "__loop_" + loopNode.getId();
        Map<String, Object> config = parseConfig(loopNode.getConfig());
        Map<String, Object> state = (Map<String, Object>) variables.getOrDefault(stateKey, null);
        List<?> items;
        int total;
        int index;
        if (state == null) {
            // 首次进入：求值数组，从第 0 项开始（首迭代不跳过——冒烟发现项修复）
            Object array = expressionEvaluator.evaluate(
                    String.valueOf(config.getOrDefault("arrayExpr", "trigger.items")), variables);
            items = array instanceof List<?> list ? list : List.of();
            total = Math.min(items.size(), Math.min(parseInt(config.get("maxIterations"), 100), 100));
            if (total <= 0) {
                loopDone(instance, loopNode, variables);
                return;
            }
            index = 0;
            state = new LinkedHashMap<>();
        } else {
            items = (List<?>) state.get("items");
            total = parseInt(state.get("total"), 0);
            index = parseInt(state.get("index"), 0) + 1;
            if (index >= total) {
                loopDone(instance, loopNode, variables);
                return;
            }
        }
        state.put("index", index);
        state.put("total", total);
        state.put("items", items);
        variables.put(stateKey, state);
        variables.put("loop", Map.of("item", items.get(index), "index", index));
        instance.setVariables(writeJson(variables));
        instanceMapper.updateById(instance);

        // 激活 item 出边目标（label=item 优先；无标签取第一条）
        WorkflowNode target = edgeTarget(loopNode.getId(), "item");
        if (target == null) {
            failInstance(instance, "loop 节点缺少 item 出边");
            return;
        }
        String upstream = writeJson(items.get(index));
        activateNode(instance, target, branchKey, index, upstream);
    }

    /** loop 完成：激活 done 出边 */
    private void loopDone(WorkflowInstance instance, WorkflowNode loopNode, Map<String, Object> variables) {
        WorkflowNode target = edgeTarget(loopNode.getId(), "done");
        if (target == null) {
            checkBranchEnd(instance);
            return;
        }
        String upstream = String.valueOf(variables.getOrDefault(varKey(loopNode), ""));
        activateNode(instance, target, "", 0, upstream);
    }

    /** return/end：求值 outputExpression → 实例 completed */
    private void activateReturn(WorkflowInstance instance, WorkflowNode node, String branchKey,
                                int iteration, String upstreamOutput, long now) {
        WorkflowNodeInstance row = insertNodeInstance(instance.getId(), node, branchKey, iteration, now);
        row.setStatus("completed");
        row.setCompletedAt(now);
        Map<String, Object> config = parseConfig(node.getConfig());
        Map<String, Object> variables = reloadVariables(instance.getId());
        Object output = config.containsKey("outputExpression")
                ? expressionEvaluator.evaluate(String.valueOf(config.get("outputExpression")), variables)
                : upstreamOutput;
        row.setOutput(jsonOrNull(writeJson(output)));
        nodeInstanceMapper.updateById(row);

        instance.setStatus("completed");
        instance.setFinalOutput(writeJson(output));
        instance.setCompletedAt(now);
        instanceMapper.updateById(instance);
        log.info("工作流实例完成: instanceId={}, return={}", instance.getId(), node.getId());
        // upstream-trigger 联动（P3 二期）：return 终态同 checkBranchEnd 通知监听器
        runtimeListeners.forEach(l -> l.onInstanceCompleted(instance));
    }

    /** 错误终态节点（53 号 §3.7）：实例 FAILED + errorMessage（onError goto 的落点） */
    private void activateErrorEnd(WorkflowInstance instance, WorkflowNode node,
                                  String branchKey, int iteration, long now) {
        WorkflowNodeInstance row = insertNodeInstance(instance.getId(), node, branchKey, iteration, now);
        row.setStatus("completed");
        row.setCompletedAt(now);
        Map<String, Object> config = parseConfig(node.getConfig());
        String message = String.valueOf(config.getOrDefault("message", "流程在错误终态结束"));
        row.setOutput(jsonOrNull(writeJson(Map.of("error", message))));
        nodeInstanceMapper.updateById(row);
        failInstance(instance, message);
    }

    /** 终止终态节点（53 号 §3.7）：其余在途分支全部 cancelled，实例 TERMINATED */
    private void activateTerminateEnd(WorkflowInstance instance, WorkflowNode node,
                                      String branchKey, int iteration, long now) {
        WorkflowNodeInstance row = insertNodeInstance(instance.getId(), node, branchKey, iteration, now);
        row.setStatus("completed");
        row.setCompletedAt(now);
        nodeInstanceMapper.updateById(row);
        // 其余在途分支全部取消（不含本行）
        List<WorkflowNodeInstance> active = nodeInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeInstance>()
                        .eq(WorkflowNodeInstance::getInstanceId, instance.getId())
                        .in(WorkflowNodeInstance::getStatus, "running", "waiting"));
        for (WorkflowNodeInstance other : active) {
            if (other.getId().equals(row.getId())) {
                continue;
            }
            other.setStatus("cancelled");
            other.setCompletedAt(now);
            nodeInstanceMapper.updateById(other);
        }
        instance.setStatus("terminated");
        instance.setCompletedAt(now);
        instanceMapper.updateById(instance);
        log.info("终止终态生效: instanceId={}, cancelled={}", instance.getId(), active.size());
        runtimeListeners.forEach(l -> l.onInstanceCancelled(instance));
    }

    // ==================== 分支终结与终态 ====================

    /** 分支终结判定：无活动节点（running/waiting）且无待决 join → 实例 completed */
    /** 分支终结判定（实例 ID 重查版：join 行级记账路径使用，规避实体缓存） */
    private void checkBranchEnd(String instanceId) {
        WorkflowInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null) {
            return;
        }
        checkBranchEnd(instance);
    }

    private void failInstanceById(String instanceId, String reason) {
        WorkflowInstance instance = instanceMapper.selectById(instanceId);
        if (instance != null) {
            failInstance(instance, reason);
        }
    }

    private void checkBranchEnd(WorkflowInstance instance) {
        Long active = nodeInstanceMapper.selectCount(new LambdaQueryWrapper<WorkflowNodeInstance>()
                .eq(WorkflowNodeInstance::getInstanceId, instance.getId())
                .in(WorkflowNodeInstance::getStatus, "running", "waiting"));
        if (active != null && active > 0) {
            return; // 其他分支仍在途
        }
        if (!"running".equals(instance.getStatus())) {
            return;
        }
        instance.setStatus("completed");
        instance.setCompletedAt(System.currentTimeMillis());
        instanceMapper.updateById(instance);
        log.info("工作流实例全部分支终结: instanceId={}", instance.getId());
        // upstream-trigger 联动：通知监听器按上游完成发起下游流程（P3 二期）
        runtimeListeners.forEach(l -> l.onInstanceCompleted(instance));
    }

    private void failInstance(WorkflowInstance instance, String reason) {
        instance.setStatus("failed");
        instance.setErrorMessage(truncate(reason));
        instance.setCompletedAt(System.currentTimeMillis());
        instanceMapper.updateById(instance);
        log.warn("工作流实例失败: instanceId={}, reason={}", instance.getId(), reason);
        runtimeListeners.forEach(l -> l.onInstanceCancelled(instance));
    }

    // ==================== 人工/外部完成入口 ====================

    /** 人工完成等待型节点（task/approval 等，P2 扩展；P1 供 delay 兜底与联调） */
    @Transactional
    public void completeNodeExternally(String instanceId, String nodeInstanceId,
                                       boolean success, String outputJson) {
        onCompletion(instanceId, new WorkflowNodeContext.Completion(nodeInstanceId, success, outputJson));
    }

    /** 测试访问（包级）：join 到达计数路径 */
    void joinArriveForTest(WorkflowInstance instance, WorkflowNode joinNode, String fromNodeId,
                           String branchKey, int iteration, String upstreamOutput) {
        joinArrive(instance, joinNode, fromNodeId, branchKey, iteration, upstreamOutput);
    }

    // ==================== 审批判定与驳回回退（52 号 P2，53 号 §3.2） ====================

    /**
     * 审批判定（审批人行完成时调用）：按策略聚合，达阈值时以聚合行完成驱动推进/驳回。
     * 聚合行（branch_key=''）完成回调携带 approved/rejectTargetNodeId，由 onCompletion 辨析。
     */
    private void judgeApproval(WorkflowInstance instance, WorkflowNode approvalNode,
                               WorkflowNodeInstance completedRow, Map<String, Object> variables) {
        Map<String, Object> config = parseConfig(approvalNode.getConfig());
        String strategy = String.valueOf(config.getOrDefault("approvalMode", "ALL"));
        double ratio = parseDouble(config.get("approveRatio"), 1.0);

        List<WorkflowNodeInstance> rows = nodeInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeInstance>()
                        .eq(WorkflowNodeInstance::getInstanceId, instance.getId())
                        .eq(WorkflowNodeInstance::getNodeId, approvalNode.getId())
                        .eq(WorkflowNodeInstance::getIteration,
                                completedRow.getIteration() == null ? 0 : completedRow.getIteration())
                        .ne(WorkflowNodeInstance::getBranchKey, ""));
        int total = rows.size();
        int approvedCount = 0;
        int rejectedCount = 0;
        for (WorkflowNodeInstance row : rows) {
            if (!"completed".equals(row.getStatus())) {
                continue;
            }
            Map<String, Object> output = parseJsonMap(row.getOutput());
            if (Boolean.parseBoolean(String.valueOf(output.getOrDefault("approved", "false")))) {
                approvedCount++;
            } else if (output.containsKey("approved")) {
                rejectedCount++;
            }
        }
        boolean approved;
        boolean rejected;
        boolean release = switch (strategy) {
            case "ANY" -> {
                approved = approvedCount >= 1;
                rejected = rejectedCount >= total;
                yield approved || rejected;
            }
            case "RATIO" -> {
                approved = total > 0 && (double) approvedCount / total >= ratio;
                rejected = total > 0 && (double) rejectedCount / total > (1 - ratio);
                yield approved || rejected;
            }
            default -> { // ALL 会签
                approved = approvedCount >= total;
                rejected = rejectedCount >= 1;
                yield approved || rejected;
            }
        };
        log.info("审批判定: instanceId={}, nodeId={}, mode={}, approved={}/{}, rejected={}, release={}",
                instance.getId(), approvalNode.getId(), strategy, approvedCount, total, rejectedCount, release);
        if (!release) {
            return; // 继续等待其余审批人
        }
        // 判定落变量并直接推进/驳回（53 号 §3.2 修订：审批人行即审计记录，无独立聚合行）
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("approved", approved);
        output.put("strategy", strategy);
        output.put("approvedCount", approvedCount);
        output.put("rejectTargetNodeId", config.get("rejectTargetNodeId"));
        variables.put(varKey(approvalNode), writeJson(output));
        instance.setVariables(writeJson(variables));
        instanceMapper.updateById(instance);
        if (approved) {
            advanceFrom(instance, approvalNode, "", 0, variables);
        } else {
            rejectAndReturn(instance, approvalNode,
                    String.valueOf(config.getOrDefault("rejectTargetNodeId", "")));
        }
    }

    /**
     * 驳回回退原语（PRD F-033/BR-023）：取消全部在途行，按
     * rejectTargetNodeId（画布业务 ID，缺省=拓扑前驱）重新激活目标节点。
     */
    private void rejectAndReturn(WorkflowInstance instance, WorkflowNode approvalNode,
                                 String rejectTargetNodeId) {
        long now = System.currentTimeMillis();
        List<WorkflowNodeInstance> active = nodeInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeInstance>()
                        .eq(WorkflowNodeInstance::getInstanceId, instance.getId())
                        .in(WorkflowNodeInstance::getStatus, "running", "waiting"));
        for (WorkflowNodeInstance row : active) {
            row.setStatus("cancelled");
            row.setCompletedAt(now);
            nodeInstanceMapper.updateById(row);
        }
        String targetBizId = rejectTargetNodeId != null && !rejectTargetNodeId.isBlank()
                && !"null".equals(rejectTargetNodeId) ? rejectTargetNodeId : null;
        WorkflowNode target = targetBizId != null
                ? nodeMapper.selectOne(new LambdaQueryWrapper<WorkflowNode>()
                        .eq(WorkflowNode::getWorkflowId, instance.getWorkflowId())
                        .and(w -> w.eq(WorkflowNode::getBizNodeId, targetBizId)
                                .or().eq(WorkflowNode::getId, targetBizId))
                        .last("LIMIT 1"))
                : topologyPredecessor(approvalNode.getId());
        if (target == null) {
            target = topologyPredecessor(approvalNode.getId());
        }
        if (target == null) {
            failInstance(instance, "审批驳回且无可回退目标节点");
            return;
        }
        instance.setStatus("running");
        instance.setCurrentNodeId(target.getId());
        instanceMapper.updateById(instance);
        // 轮次 +1（P2 冒烟发现项：同节点重复审批的行唯一键按 iteration 区分，
        // 回退重审是新轮次——旧轮次行留存作审计）
        int nextIteration = nodeInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeInstance>()
                        .eq(WorkflowNodeInstance::getInstanceId, instance.getId())
                        .eq(WorkflowNodeInstance::getNodeId, target.getId()))
                .stream().mapToInt(r -> r.getIteration() == null ? 0 : r.getIteration()).max().orElse(0) + 1;
        log.info("审批驳回回退: instanceId={}, approvalNodeId={}, target={}, iteration={}",
                instance.getId(), approvalNode.getId(), target.getId(), nextIteration);
        activateNode(instance, target, "", nextIteration, "");
    }

    /** 拓扑前驱（驳回缺省目标）：指向本节点的边的 from（按 sort/创建序取首条） */
    private WorkflowNode topologyPredecessor(String nodeId) {
        List<WorkflowTransition> edges = transitionMapper.selectList(
                new LambdaQueryWrapper<WorkflowTransition>()
                        .eq(WorkflowTransition::getToNodeId, nodeId));
        return edges.isEmpty() ? null : nodeMapper.selectById(edges.get(0).getFromNodeId());
    }

    private double parseDouble(Object value, double defaultValue) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 非数字按缺省
            }
        }
        return defaultValue;
    }

    // ==================== 辅助 ====================

    /** 实例推进互斥锁（单实例部署；多副本升级分布式锁） */
    private void lock(String instanceId, Runnable action) {
        ReentrantLock lock = instanceLocks.computeIfAbsent(instanceId, k -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
            instanceLocks.remove(instanceId, lock);
        }
    }

    private WorkflowNodeInstance insertNodeInstance(String instanceId, WorkflowNode node,
                                                    String branchKey, int iteration, long now) {
        WorkflowNodeInstance nodeInstance = new WorkflowNodeInstance();
        nodeInstance.setInstanceId(instanceId);
        nodeInstance.setNodeId(node.getId());
        nodeInstance.setNodeName(node.getNodeName());
        nodeInstance.setNodeType(node.getNodeType());
        nodeInstance.setBranchKey(branchKey == null ? "" : branchKey);
        nodeInstance.setIteration(iteration);
        nodeInstance.setStatus("pending");
        nodeInstance.setRetryCount(0);
        nodeInstance.setStartedAt(now);
        nodeInstanceMapper.insert(nodeInstance);
        return nodeInstance;
    }

    private List<WorkflowTransition> outgoing(String nodeId) {
        return transitionMapper.selectList(new LambdaQueryWrapper<WorkflowTransition>()
                .eq(WorkflowTransition::getFromNodeId, nodeId)
                .orderByAsc(WorkflowTransition::getSortOrder));
    }

    /** 按 label 找出边目标（label 缺省匹配第一条） */
    private WorkflowNode edgeTarget(String nodeId, String label) {
        List<WorkflowTransition> edges = outgoing(nodeId);
        WorkflowTransition matched = null;
        WorkflowTransition first = null;
        for (WorkflowTransition edge : edges) {
            if (first == null) {
                first = edge;
            }
            if (label != null && label.equalsIgnoreCase(edge.getLabel())) {
                matched = edge;
                break;
            }
        }
        WorkflowTransition selected = matched != null ? matched
                : edges.stream().filter(e -> e.getLabel() == null || e.getLabel().isBlank()).findFirst().orElse(first);
        return selected != null ? nodeMapper.selectById(selected.getToNodeId()) : null;
    }

    private boolean isLoopNode(String nodeId) {
        WorkflowNode node = nodeMapper.selectById(nodeId);
        return node != null && "loop".equals(node.getNodeType());
    }

    /**
     * 变量重载（冒烟诊断修复）：必须按单列 select 重查而非读传入实体——
     * 同事务内 selectById 会命中 MyBatis 一级缓存返回陈旧快照，
     * 并行分支完成回调的变量写与 join 记账因此"丢失"。
     */
    private Map<String, Object> reloadVariables(String instanceId) {
        WorkflowInstance fresh = instanceMapper.selectOne(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .select(WorkflowInstance::getVariables, WorkflowInstance::getStatus)
                        .eq(WorkflowInstance::getId, instanceId)
                        .last("LIMIT 1"));
        return parseJsonMap(fresh != null ? fresh.getVariables() : null);
    }

    private Map<String, Object> parseConfig(String configJson) {
        return parseJsonMap(configJson);
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return com.veloflow.engine.commons.VeloflowJson.MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return com.veloflow.engine.commons.VeloflowJson.MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private String writeJson(Object value) {
        try {
            return com.veloflow.engine.commons.VeloflowJson.MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private int parseInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 非数字配置按缺省处理
            }
        }
        return defaultValue;
    }

    /** 变量键：节点业务 ID 优先（画布表达式引用键），回退行 ID（51 号 §五） */
    private String varKey(WorkflowNode node) {
        if (node == null) {
            return "__unknown__";
        }
        return node.getBizNodeId() != null && !node.getBizNodeId().isBlank()
                ? node.getBizNodeId() : node.getId();
    }

    /** JSON 列防御：空串/空白视为 NULL（MySQL JSON 列拒绝空文档） */
    private String jsonOrNull(String json) {
        return json == null || json.isBlank() ? null : json;
    }

    private String truncate(String text) {
        return text != null && text.length() > 500 ? text.substring(0, 500) : text;
    }
}
