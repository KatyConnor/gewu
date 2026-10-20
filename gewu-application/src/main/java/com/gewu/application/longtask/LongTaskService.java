package com.gewu.application.longtask;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gewu.application.longtask.dto.CreateLongTaskCommand;
import com.gewu.application.session.SessionContextService;
import com.gewu.application.workspace.DevWorkspaceService;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.agent.engine.core.ReactAgentExecutor;
import com.gewu.agent.engine.core.event.AgentEvent;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.longtask.AgentLongTask;
import com.gewu.domain.longtask.AgentLongTaskReview;
import com.gewu.domain.longtask.AgentLongTaskSegment;
import com.gewu.domain.session.Session;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.longtask.AgentLongTaskMapper;
import com.gewu.infrastructure.mapper.longtask.AgentLongTaskReviewMapper;
import com.gewu.infrastructure.mapper.longtask.AgentLongTaskSegmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 持久化目标驱动长任务服务（docs/design/45）：状态机 + 分段执行 + 目标评估 + 人工审核闭环。
 * <p>每"段"= 一次受段预算约束的 ReactAgentExecutor 回合执行（复用会话上下文/转向/子代理/
 * 降级等全部既有机制）；段间落检查点，由调度器按冷却节奏驱动，进程重启从检查点续跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LongTaskService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DAY_MS = 86_400_000L;

    private final AgentLongTaskMapper taskMapper;
    private final AgentLongTaskSegmentMapper segmentMapper;
    private final AgentLongTaskReviewMapper reviewMapper;
    private final SessionMapper sessionMapper;
    private final SessionContextService sessionContextService;
    private final DevWorkspaceService devWorkspaceService;
    private final ReactAgentExecutor reactAgentExecutor;
    private final GoalEvaluator goalEvaluator;
    private final com.gewu.application.entry.AgentEntryFileService entryFileService;

    @Value("${agent.longtask.segment-timeout-seconds:1800}")
    private int segmentTimeoutSeconds;

    @Value("${agent.longtask.blocked-after-failures:3}")
    private int blockedAfterFailures;

    @Value("${agent.longtask.default-cooldown-minutes:5}")
    private int defaultCooldownMinutes;

    // ==================== 查询 ====================

    public List<AgentLongTask> listMy(String userId, String status) {
        return taskMapper.selectList(new LambdaQueryWrapper<AgentLongTask>()
                .eq(AgentLongTask::getCreatedBy, userId)
                .eq(status != null && !status.isBlank(), AgentLongTask::getStatus, status)
                .eq(AgentLongTask::getDeleted, 0)
                .orderByDesc(AgentLongTask::getUpdatedAt));
    }

    public AgentLongTask getOwned(String id, String userId) {
        AgentLongTask task = taskMapper.selectById(id);
        if (task == null || task.getDeleted() != null && task.getDeleted() == 1) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "长任务不存在");
        }
        if (!task.getCreatedBy().equals(userId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "无权访问该长任务");
        }
        return task;
    }

    public List<AgentLongTaskSegment> segmentsOf(String taskId) {
        return segmentMapper.selectList(new LambdaQueryWrapper<AgentLongTaskSegment>()
                .eq(AgentLongTaskSegment::getTaskId, taskId)
                .orderByAsc(AgentLongTaskSegment::getSeq));
    }

    public List<AgentLongTaskReview> reviewsOf(String taskId) {
        return reviewMapper.selectList(new LambdaQueryWrapper<AgentLongTaskReview>()
                .eq(AgentLongTaskReview::getTaskId, taskId)
                .orderByDesc(AgentLongTaskReview::getCreatedAt));
    }

    // ==================== 创建 ====================

    public AgentLongTask create(CreateLongTaskCommand cmd) {
        String userId = UserContext.currentUserId();
        Session session = sessionMapper.selectById(cmd.getSessionId());
        if (session == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "会话不存在");
        }
        if (!session.getCreatedBy().equals(userId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "仅会话创建者可发起长任务");
        }
        // criteria 校验（statement 必须可解析出）
        GoalCriteria criteria = GoalCriteria.parse(cmd.getCriteriaJson());
        if (criteria.getStatement() == null || criteria.getStatement().isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "验收标准缺少 statement");
        }
        long now = System.currentTimeMillis();
        int maxDays = cmd.getMaxDays() != null && cmd.getMaxDays() > 0 ? cmd.getMaxDays() : 7;

        AgentLongTask task = new AgentLongTask();
        task.setId(Ulid.next());
        task.setSessionId(cmd.getSessionId());
        task.setTitle(cmd.getTitle() != null && !cmd.getTitle().isBlank()
                ? cmd.getTitle() : cmd.getGoalText().substring(0, Math.min(60, cmd.getGoalText().length())));
        task.setGoalText(cmd.getGoalText());
        task.setCriteriaJson(cmd.getCriteriaJson());
        task.setReviewRequired(criteria.isReviewRequired() ? 1 : 0);
        task.setAgentId(cmd.getAgentId() != null && !cmd.getAgentId().isBlank()
                ? cmd.getAgentId() : session.getAgent());
        task.setProviderCode(cmd.getProviderCode());
        task.setModel(cmd.getModel());
        task.setStatus(AgentLongTask.STATUS_QUEUED);
        task.setSegmentCount(0);
        task.setMaxSegments(100);
        task.setMaxDays(maxDays);
        task.setDeadlineAt(now + maxDays * DAY_MS);
        task.setCooldownMinutes(defaultCooldownMinutes);
        task.setDailyTokenCap(cmd.getDailyTokenCap() != null ? cmd.getDailyTokenCap() : 0);
        task.setTotalTokens(0L);
        task.setFailStreak(0);
        task.setNextRunAt(now);
        task.setProgressSummary("待首段执行");
        task.setCreatedBy(userId);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);

        // 会话内可见：创建即落一条用户侧通告（长任务的执行消息流复用该会话）
        sessionContextService.appendUserMessage(cmd.getSessionId(), userId,
                "[长任务已创建] 目标：" + cmd.getGoalText() + "\n验收标准：" + criteria.getStatement()
                        + "\n执行期限：" + maxDays + " 天。任务将按冷却节奏分段推进，达标后"
                        + (criteria.isReviewRequired() ? "需人工审核确认。" : "自动完成。"),
                "lt-create-" + task.getId());
        return task;
    }

    // ==================== 人工控制 ====================

    public void pause(String id, String userId) {
        AgentLongTask task = getOwned(id, userId);
        if (!AgentLongTask.STATUS_RUNNING.equals(task.getStatus())
                && !AgentLongTask.STATUS_QUEUED.equals(task.getStatus())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "当前状态不可暂停: " + task.getStatus());
        }
        task.setStatus(AgentLongTask.STATUS_PAUSED);
        task.setNextRunAt(Long.MAX_VALUE / 2);
        task.setUpdatedAt(System.currentTimeMillis());
        taskMapper.updateById(task);
    }

    public void resume(String id, String userId) {
        AgentLongTask task = getOwned(id, userId);
        if (!AgentLongTask.STATUS_PAUSED.equals(task.getStatus())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "任务未处于暂停状态");
        }
        task.setStatus(AgentLongTask.STATUS_RUNNING);
        task.setNextRunAt(System.currentTimeMillis());
        task.setUpdatedAt(System.currentTimeMillis());
        taskMapper.updateById(task);
    }

    public void cancel(String id, String userId) {
        AgentLongTask task = getOwned(id, userId);
        if (isTerminal(task.getStatus())) {
            return;
        }
        task.setStatus(AgentLongTask.STATUS_CANCELLED);
        task.setNextRunAt(Long.MAX_VALUE / 2);
        task.setUpdatedAt(System.currentTimeMillis());
        taskMapper.updateById(task);
    }

    /** 人工审核：通过=COMPLETED；驳回=反馈注入下一段（任务继续） */
    public void review(String id, String verdict, String comment, String reviewer) {
        AgentLongTask task = getOwned(id, reviewer);
        if (!AgentLongTask.STATUS_WAITING_REVIEW.equals(task.getStatus())
                && !AgentLongTask.STATUS_BLOCKED.equals(task.getStatus())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID,
                    "当前状态不可审核: " + task.getStatus() + "（仅 WAITING_REVIEW/BLOCKED）");
        }
        boolean approved = "approved".equalsIgnoreCase(verdict);
        AgentLongTaskReview review = AgentLongTaskReview.builder()
                .taskId(id)
                .segmentSeq(task.getSegmentCount())
                .kind(AgentLongTaskReview.KIND_GOAL_REACHED)
                .verdict(approved ? AgentLongTaskReview.VERDICT_APPROVED
                        : AgentLongTaskReview.VERDICT_REJECTED)
                .comment(comment)
                .reviewer(reviewer)
                .build();
        long nowMs = System.currentTimeMillis();
        review.setCreatedAt(nowMs);
        review.setUpdatedAt(nowMs);
        if (review.getId() == null || review.getId().isBlank()) {
            review.setId(Ulid.next());
        }
        reviewMapper.insert(review);

        if (approved) {
            task.setStatus(AgentLongTask.STATUS_COMPLETED);
        } else {
            // 驳回：审核意见作为补充指令注入下一段，任务继续推进
            task.setStatus(AgentLongTask.STATUS_RUNNING);
            task.setNextRunAt(System.currentTimeMillis());
            task.setSteeringNote(comment != null && !comment.isBlank()
                    ? "【审核驳回反馈】" + comment : null);
        }
        task.setUpdatedAt(System.currentTimeMillis());
        taskMapper.updateById(task);
        sessionContextService.appendUserMessage(id, reviewer,
                "[审核" + (approved ? "通过" : "驳回") + "] " + (comment == null ? "" : comment),
                "lt-review-" + review.getId());
    }

    /** 补充指令：写入下一段种子（长任务期间随时纠偏） */
    public void steer(String id, String content, String userId) {
        AgentLongTask task = getOwned(id, userId);
        task.setSteeringNote(content);
        task.setUpdatedAt(System.currentTimeMillis());
        taskMapper.updateById(task);
        sessionContextService.appendUserMessage(id, userId,
                "[补充指令] " + content, "lt-steer-" + System.currentTimeMillis());
    }

    private static boolean isTerminal(String status) {
        return AgentLongTask.STATUS_COMPLETED.equals(status)
                || AgentLongTask.STATUS_FAILED.equals(status)
                || AgentLongTask.STATUS_EXPIRED.equals(status)
                || AgentLongTask.STATUS_CANCELLED.equals(status);
    }

    // ==================== 分段执行（调度器驱动） ====================

    /**
     * 调度入口：领取到期的 QUEUED/RUNNING 任务，逐个执行一个段。
     * 单线程调度器串行执行；多实例演进见设计文档 §9。
     */
    public void runDueSegments() {
        List<AgentLongTask> due = taskMapper.selectList(new LambdaQueryWrapper<AgentLongTask>()
                .in(AgentLongTask::getStatus, AgentLongTask.STATUS_QUEUED, AgentLongTask.STATUS_RUNNING)
                .le(AgentLongTask::getNextRunAt, System.currentTimeMillis())
                .eq(AgentLongTask::getDeleted, 0)
                .orderByAsc(AgentLongTask::getNextRunAt)
                .last("LIMIT 3"));
        for (AgentLongTask task : due) {
            try {
                runSegment(task);
            } catch (Exception e) {
                log.error("长任务段执行失败: taskId={}", task.getId(), e);
                markSegmentInterrupted(task.getId());
            }
        }
    }

    /** 执行一个段：种子提示 → agent 回合 → 检查点 → 目标评估 → 状态迁移 */
    public void runSegment(AgentLongTask task) {
        long now = System.currentTimeMillis();
        if (task.getDeadlineAt() > 0 && now > task.getDeadlineAt()) {
            task.setStatus(AgentLongTask.STATUS_EXPIRED);
            task.setUpdatedAt(now);
            taskMapper.updateById(task);
            return;
        }
        // 日预算守卫（docs/design/45 §8）：当日 token 累计超限 → PAUSED 至次日 0 点
        if (dailyBudgetExceeded(task)) {
            pauseUntilNextDay(task);
            return;
        }
        int seq = task.getSegmentCount() + 1;
        GoalCriteria criteria = GoalCriteria.parse(task.getCriteriaJson());

        // 段前检查：把上次遗留的 running 段标记为中断（崩溃恢复）
        markStaleRunningSegments(task.getId());

        // 种子提示：目标 + 验收标准 + 检查点摘要 + 审核反馈 + 补充指令
        String seed = buildSeedPrompt(task, seq, criteria);
        String seedClientId = "lt-seg-" + task.getId() + "-" + seq;
        sessionContextService.appendUserMessage(task.getSessionId(), task.getCreatedBy(),
                seed, seedClientId);

        AgentLongTaskSegment segment = new AgentLongTaskSegment();
        segment.setId(Ulid.next());
        segment.setTaskId(task.getId());
        segment.setSeq(seq);
        segment.setStatus(AgentLongTaskSegment.STATUS_RUNNING);
        segment.setSeedPrompt(seed);
        segment.setStartedAt(now);
        segmentMapper.insert(segment);

        // 沙箱就绪（目标机械检查/文件操作需要）
        String sandboxId = devWorkspaceService
                .ensureDevSandboxForUser(task.getCreatedBy()).getSandboxId();

        // 段执行：复用会话回合执行链（转向/子代理/文件工具/降级全部生效）
        long segTimeoutMs = segmentTimeoutSeconds * 1000L;
        com.gewu.agent.engine.core.AgentTask segTask = AgentTask.builder()
                .agentId(task.getAgentId())
                .sessionId(task.getSessionId())
                .userId(task.getCreatedBy())
                .message(seed)
                .build();
        // 长任务段入口调度指令继承（评审 F-203：段自建任务不得静默丢失入口指令）
        entryFileService.applyToTask(segTask);
        List<AgentEvent> events = reactAgentExecutor.executeStream(segTask)
                .take(Duration.ofMillis(segTimeoutMs))
                .collectList()
                .block(Duration.ofMillis(segTimeoutMs + 120_000L));

        String content = new StringBuilder().toString();
        StringBuilder contentAcc = new StringBuilder();
        String error = null;
        long tokens = 0;
        if (events != null) {
            StringBuilder acc = new StringBuilder();
            for (AgentEvent ev : events) {
                if (AgentEvent.CONTENT.equals(ev.getType()) && ev.getContent() != null) {
                    acc.append(ev.getContent());
                } else if (AgentEvent.ERROR.equals(ev.getType()) && ev.getErrorMessage() != null) {
                    error = ev.getErrorMessage();
                }
            }
            content = acc.toString();
        }
        String summary = !content.isBlank() ? tail(content, 2000)
                : (error != null ? "本段失败: " + error : "（无输出）");

        segment.setStatus(error != null
                ? AgentLongTaskSegment.STATUS_FAILED : AgentLongTaskSegment.STATUS_SUCCESS);
        segment.setSummary(summary);
        segment.setTokens(tokens);
        segment.setErrorMsg(error);
        segment.setFinishedAt(System.currentTimeMillis());

        // 目标评估（机械检查 + 判官）
        GoalEvaluator.GoalEvalResult eval = goalEvaluator.evaluate(task, sandboxId, buildRepoContext(task));
        segment.setGoalEval(toJson(eval));
        segmentMapper.updateById(segment);

        // 段收尾可见性：段摘要作为助手消息落库（会话页可见）
        sessionContextService.appendAssistantMessage(task.getSessionId(),
                "[第 " + seq + " 段完成] " + summary, null, seedClientId + ":ai");

        // 状态迁移
        task.setSegmentCount(seq);
        task.setTotalTokens((task.getTotalTokens() == null ? 0 : task.getTotalTokens()) + tokens);
        task.setProgressSummary(eval.summary());
        if (eval.achieved()) {
            task.setFailStreak(0);
            if (task.getReviewRequired() != null && task.getReviewRequired() == 1) {
                task.setStatus(AgentLongTask.STATUS_WAITING_REVIEW);
                insertReview(task.getId(), seq, AgentLongTaskReview.KIND_GOAL_REACHED);
            } else {
                task.setStatus(AgentLongTask.STATUS_COMPLETED);
            }
        } else {
            task.setFailStreak((task.getFailStreak() == null ? 0 : task.getFailStreak()) + 1);
            if (task.getFailStreak() >= blockedAfterFailures) {
                task.setStatus(AgentLongTask.STATUS_BLOCKED);
                insertReview(task.getId(), seq, AgentLongTaskReview.KIND_BLOCKED);
            } else {
                task.setStatus(AgentLongTask.STATUS_RUNNING);
            }
            // 未达标：冷却后继续下一段（种子自动带上审核反馈/补充指令）
        }
        task.setNextRunAt(AgentLongTask.STATUS_RUNNING.equals(task.getStatus())
                ? System.currentTimeMillis() + task.getCooldownMinutes() * 60_000L
                : System.currentTimeMillis());
        task.setUpdatedAt(System.currentTimeMillis());
        taskMapper.updateById(task);
        log.info("长任务段完成: taskId={}, seq={}, status={}, eval={}",
                task.getId(), seq, task.getStatus(), eval.summary());
    }

    /** 日预算守卫：超限时任务转 PAUSED 至次日 0 点 */
    private boolean dailyBudgetExceeded(AgentLongTask task) {
        return task.getDailyTokenCap() != null && task.getDailyTokenCap() > 0
                && task.getTotalTokens() >= task.getDailyTokenCap();
    }

    private void pauseUntilNextDay(AgentLongTask task) {
        long now = System.currentTimeMillis();
        long nextDay = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        task.setStatus(AgentLongTask.STATUS_PAUSED);
        task.setNextRunAt(nextDay);
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
    }

    // ==================== 检查点与种子 ====================

    private String buildSeedPrompt(AgentLongTask task, int seq, GoalCriteria criteria) {
        StringBuilder sb = new StringBuilder();
        sb.append("【长期任务 · 第 ").append(seq).append(" 段】\n");
        sb.append("【目标】").append(task.getGoalText()).append('\n');
        sb.append("【验收标准】").append(criteria.getStatement()).append('\n');
        // 检查点摘要（上一段结束时定格）
        AgentLongTaskSegment prev = latestSegment(task.getId(), seq - 1);
        if (prev != null && prev.getCheckpoint() != null) {
            try {
                JsonNode cp = MAPPER.readTree(prev.getCheckpoint());
                sb.append("【检查点摘要】")
                        .append(cp.path("progressSummary").asText("（无）")).append('\n');
                sb.append("【上次文件清单】")
                        .append(cp.path("fileInventory").toString()).append('\n');
                String directive = cp.path("nextDirective").asText("");
                if (!directive.isBlank()) {
                    sb.append("【上次遗留建议】").append(directive).append('\n');
                }
            } catch (Exception e) {
                log.debug("检查点解析失败: {}", e.getMessage());
            }
        }
        if (task.getSteeringNote() != null && !task.getSteeringNote().isBlank()) {
            sb.append("【用户补充指令】").append(task.getSteeringNote()).append('\n');
        }
        sb.append("请先核对仓库现状与以上记录的差异，再继续推进直至目标达成或本段预算耗尽。");
        // 补充指令已消费（进入本段种子）
        task.setSteeringNote(null);
        return sb.toString();
    }

    private AgentLongTaskSegment latestSegment(String taskId, int seq) {
        return segmentMapper.selectOne(new LambdaQueryWrapper<AgentLongTaskSegment>()
                .eq(AgentLongTaskSegment::getTaskId, taskId)
                .eq(AgentLongTaskSegment::getSeq, seq)
                .last("LIMIT 1"));
    }

    private void markStaleRunningSegments(String taskId) {
        List<AgentLongTaskSegment> stale = segmentMapper.selectList(
                new LambdaQueryWrapper<AgentLongTaskSegment>()
                        .eq(AgentLongTaskSegment::getTaskId, taskId)
                        .eq(AgentLongTaskSegment::getStatus, AgentLongTaskSegment.STATUS_RUNNING));
        for (AgentLongTaskSegment s : stale) {
            s.setStatus(AgentLongTaskSegment.STATUS_INTERRUPTED);
            s.setErrorMsg("进程中断，段被标记中断（检查点续跑）");
            s.setFinishedAt(System.currentTimeMillis());
            segmentMapper.updateById(s);
        }
    }

    private void markSegmentInterrupted(String taskId) {
        List<AgentLongTaskSegment> running = segmentMapper.selectList(
                new LambdaQueryWrapper<AgentLongTaskSegment>()
                        .eq(AgentLongTaskSegment::getTaskId, taskId)
                        .eq(AgentLongTaskSegment::getStatus, AgentLongTaskSegment.STATUS_RUNNING));
        for (AgentLongTaskSegment s : running) {
            s.setStatus(AgentLongTaskSegment.STATUS_INTERRUPTED);
            s.setErrorMsg("执行异常中断");
            s.setFinishedAt(System.currentTimeMillis());
            segmentMapper.updateById(s);
        }
    }

    private void insertReview(String taskId, int seq, String kind) {
        AgentLongTaskReview review = AgentLongTaskReview.builder()
                .taskId(taskId)
                .segmentSeq(seq)
                .kind(kind)
                .verdict(AgentLongTaskReview.VERDICT_PENDING)
                .build();
        long nowMs = System.currentTimeMillis();
        review.setCreatedAt(nowMs);
        review.setUpdatedAt(nowMs);
        review.setId(Ulid.next());
        reviewMapper.insert(review);
    }

    private String buildRepoContext(AgentLongTask task) {
        // 仓库产物上下文：最新进度摘要 + 进度总结（判官输入），M1 从简
        StringBuilder sb = new StringBuilder();
        if (task.getProgressSummary() != null) {
            sb.append("进度: ").append(task.getProgressSummary());
        }
        return sb.toString();
    }

    private String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String tail(String s, int n) {
        String t = s == null ? "" : s.trim();
        return t.length() > n ? "…" + t.substring(t.length() - n) : t;
    }
}
