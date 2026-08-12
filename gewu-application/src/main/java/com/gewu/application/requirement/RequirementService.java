package com.gewu.application.requirement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.requirement.dto.*;
import com.gewu.common.context.UserContext;
import com.gewu.common.enums.RequirementStatus;
import com.gewu.common.enums.RequirementType;
import com.gewu.common.enums.ReviewType;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.common.util.PermissionChecker;
import static com.gewu.common.enums.RequirementPermission.*;
import com.gewu.domain.requirement.*;
import com.gewu.domain.user.UserAccount;
import com.gewu.infrastructure.mapper.*;
import com.gewu.infrastructure.util.ULIDUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementService {

    private final RequirementMapper requirementMapper;
    private final RequirementReviewMapper reviewMapper;
    private final RequirementTaskMapper taskMapper;
    private final RequirementCommentMapper commentMapper;
    private final UserAccountMapper userAccountMapper;

    // 需求编号计数器（年份 -> 计数器）
    private static final ConcurrentHashMap<String, AtomicInteger> CODE_COUNTERS = new ConcurrentHashMap<>();

    // ==================== 需求 CRUD ====================

    public PageResult<RequirementDTO> listRequirements(RequirementQuery query) {
        LambdaQueryWrapper<Requirement> wrapper = buildQueryWrapper(query);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Requirement> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(query.getPage(), query.getSize());
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Requirement> result = requirementMapper.selectPage(page, wrapper);
        List<RequirementDTO> dtos = result.getRecords().stream().map(this::toDTO).toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    public RequirementDTO getRequirement(String id) {
        Requirement req = getOrThrow(id);
        return toDTO(req);
    }

    @Transactional
    public RequirementDTO createRequirement(CreateRequirementCommand command) {
        String userId = currentUserId();
        // 权限检查
        PermissionChecker.checkPermission(REQUIREMENT_CREATE);

        Requirement req = new Requirement();
        req.setId(ULIDUtil.next());
        req.setRequirementCode(generateRequirementCode());
        req.setTitle(command.getTitle());
        req.setDescription(command.getDescription());
        req.setType(command.getType() != null ? command.getType() : RequirementType.STORY.getCode());
        req.setPriority(command.getPriority() != null ? command.getPriority() : 2);
        req.setStatus(RequirementStatus.DRAFT.getCode());
        req.setReporterId(userId);
        req.setAssigneeId(command.getAssigneeId());
        req.setParentId(command.getParentId());
        req.setProjectId(command.getProjectId());
        req.setSessionIds(command.getSessionIds());
        req.setDocumentIds(command.getDocumentIds());
        req.setDueDate(command.getDueDate());
        req.setStoryPoint(command.getStoryPoint());
        req.setEstimatedHours(command.getEstimatedHours());
        req.setCreatedAt(System.currentTimeMillis());
        req.setUpdatedAt(System.currentTimeMillis());
        req.setCreatedBy(userId);
        req.setUpdatedBy(userId);
        requirementMapper.insert(req);
        return toDTO(req);
    }

    @Transactional
    public RequirementDTO updateRequirement(String id, UpdateRequirementCommand command) {
        Requirement req = getOrThrow(id);
        checkEditable(req);
        // 权限检查：创建人或编辑权限
        PermissionChecker.checkRequirementEditPermission(req.getCreatedBy());

        if (command.getTitle() != null) req.setTitle(command.getTitle());
        if (command.getDescription() != null) req.setDescription(command.getDescription());
        if (command.getType() != null) req.setType(command.getType());
        if (command.getPriority() != null) req.setPriority(command.getPriority());
        if (command.getAssigneeId() != null) req.setAssigneeId(command.getAssigneeId());
        if (command.getDesignerId() != null) req.setDesignerId(command.getDesignerId());
        if (command.getDeveloperId() != null) req.setDeveloperId(command.getDeveloperId());
        if (command.getTesterId() != null) req.setTesterId(command.getTesterId());
        if (command.getDueDate() != null) req.setDueDate(command.getDueDate());
        if (command.getStoryPoint() != null) req.setStoryPoint(command.getStoryPoint());
        if (command.getEstimatedHours() != null) req.setEstimatedHours(command.getEstimatedHours());
        if (command.getSessionIds() != null) req.setSessionIds(command.getSessionIds());
        if (command.getDocumentIds() != null) req.setDocumentIds(command.getDocumentIds());
        if (command.getDesignDoc() != null) req.setDesignDoc(command.getDesignDoc());
        if (command.getPlanDoc() != null) req.setPlanDoc(command.getPlanDoc());
        if (command.getTestDoc() != null) req.setTestDoc(command.getTestDoc());

        req.setUpdatedAt(System.currentTimeMillis());
        req.setUpdatedBy(currentUserId());
        requirementMapper.updateById(req);
        return toDTO(req);
    }

    @Transactional
    public void deleteRequirement(String id) {
        Requirement req = getOrThrow(id);
        checkEditable(req);
        // 权限检查：创建人或管理员权限
        PermissionChecker.checkRequirementEditPermission(req.getCreatedBy());
        req.setDeleted(1);
        req.setUpdatedAt(System.currentTimeMillis());
        req.setUpdatedBy(currentUserId());
        requirementMapper.updateById(req);
    }

    // ==================== 状态流转 ====================

    @Transactional
    public RequirementDTO updateStatus(String id, UpdateRequirementStatusCommand command) {
        Requirement req = getOrThrow(id);
        String newStatus = command.getStatus();
        validateStatusTransition(req.getStatus(), newStatus);

        req.setStatus(newStatus);
        req.setUpdatedAt(System.currentTimeMillis());
        req.setUpdatedBy(currentUserId());

        // 记录关键时间点
        long now = System.currentTimeMillis();
        if (RequirementStatus.IN_DEV.getCode().equals(newStatus)) {
            req.setStartedAt(now);
        }
        if (RequirementStatus.RELEASED.getCode().equals(newStatus)) {
            req.setCompletedAt(now);
            req.setReleasedAt(now);
        }
        if (RequirementStatus.CANCELLED.getCode().equals(newStatus)) {
            req.setCancelledAt(now);
            req.setCancelReason(command.getReason());
        }

        requirementMapper.updateById(req);
        return toDTO(req);
    }

    // ==================== 评审管理 ====================

    @Transactional
    public void submitReview(String id, SubmitReviewCommand command) {
        Requirement req = getOrThrow(id);
        if (!RequirementStatus.DRAFT.getCode().equals(req.getStatus())
                && !RequirementStatus.PENDING_REVIEW.getCode().equals(req.getStatus())) {
            throw BusinessException.of(ResultCode.REQUIREMENT_STATUS_INVALID, "只有草稿或待评审状态的需求可以提交评审");
        }
        // 权限检查：提交评审权限
        PermissionChecker.checkPermission(REQUIREMENT_REVIEW_SUBMIT);

        req.setStatus(RequirementStatus.PENDING_REVIEW.getCode());
        req.setUpdatedAt(System.currentTimeMillis());
        req.setUpdatedBy(currentUserId());
        requirementMapper.updateById(req);
        log.info("需求已提交评审: requirementId={}, reviewType={}", id, command.getReviewType());
    }

    @Transactional
    public void submitReviewOpinion(String id, SubmitReviewOpinionCommand command) {
        Requirement req = getOrThrow(id);
        // 权限检查：执行评审权限
        PermissionChecker.checkPermission(REVIEW_EXECUTE);

        RequirementReview review = new RequirementReview();
        review.setId(ULIDUtil.next());
        review.setRequirementId(id);
        review.setReviewType(command.getReviewType());
        review.setReviewerId(currentUserId());
        review.setReviewResult(command.getReviewResult());
        review.setReviewComment(command.getReviewComment());
        review.setReviewAttachments(command.getReviewAttachments());
        review.setReviewOrder(0);
        review.setCompletedAt(System.currentTimeMillis());
        review.setCreatedAt(System.currentTimeMillis());
        review.setCreatedBy(currentUserId());
        reviewMapper.insert(review);

        // 更新需求状态
        if ("APPROVED".equals(command.getReviewResult())) {
            req.setStatus(RequirementStatus.APPROVED.getCode());
        } else if ("REJECTED".equals(command.getReviewResult())) {
            req.setStatus(RequirementStatus.DRAFT.getCode());
        }
        req.setUpdatedAt(System.currentTimeMillis());
        req.setUpdatedBy(currentUserId());
        requirementMapper.updateById(req);
        log.info("评审意见已提交: requirementId={}, result={}", id, command.getReviewResult());
    }

    public List<RequirementReviewDTO> getReviews(String requirementId) {
        return reviewMapper.selectList(
                new LambdaQueryWrapper<RequirementReview>()
                        .eq(RequirementReview::getRequirementId, requirementId)
                        .orderByAsc(RequirementReview::getReviewOrder))
                .stream().map(this::toReviewDTO).toList();
    }

    // ==================== 任务管理 ====================

    public List<RequirementTaskDTO> getTasks(String requirementId) {
        return taskMapper.selectList(
                new LambdaQueryWrapper<RequirementTask>()
                        .eq(RequirementTask::getRequirementId, requirementId)
                        .eq(RequirementTask::getDeleted, 0))
                .stream().map(this::toTaskDTO).toList();
    }

    @Transactional
    public RequirementTaskDTO createTask(String requirementId, CreateTaskCommand command) {
        getOrThrow(requirementId);
        // 权限检查：创建任务权限
        PermissionChecker.checkPermission(TASK_CREATE);
        RequirementTask task = new RequirementTask();
        task.setId(ULIDUtil.next());
        task.setRequirementId(requirementId);
        task.setTaskCode(generateTaskCode());
        task.setTitle(command.getTitle());
        task.setDescription(command.getDescription());
        task.setAssigneeId(command.getAssigneeId());
        task.setStatus("PENDING");
        task.setEstimatedHours(command.getEstimatedHours());
        task.setCreatedAt(System.currentTimeMillis());
        task.setUpdatedAt(System.currentTimeMillis());
        task.setCreatedBy(currentUserId());
        task.setUpdatedBy(currentUserId());
        taskMapper.insert(task);
        return toTaskDTO(task);
    }

    @Transactional
    public RequirementTaskDTO updateTask(String taskId, UpdateTaskCommand command) {
        RequirementTask task = getTaskOrThrow(taskId);
        if (command.getTitle() != null) task.setTitle(command.getTitle());
        if (command.getDescription() != null) task.setDescription(command.getDescription());
        if (command.getAssigneeId() != null) task.setAssigneeId(command.getAssigneeId());
        if (command.getStatus() != null) task.setStatus(command.getStatus());
        if (command.getActualHours() != null) task.setActualHours(command.getActualHours());

        long now = System.currentTimeMillis();
        if ("IN_PROGRESS".equals(command.getStatus()) && task.getStartedAt() == null) {
            task.setStartedAt(now);
        }
        if ("COMPLETED".equals(command.getStatus()) && task.getCompletedAt() == null) {
            task.setCompletedAt(now);
        }

        task.setUpdatedAt(now);
        task.setUpdatedBy(currentUserId());
        taskMapper.updateById(task);
        return toTaskDTO(task);
    }

    @Transactional
    public void deleteTask(String taskId) {
        RequirementTask task = getTaskOrThrow(taskId);
        task.setDeleted(1);
        task.setUpdatedAt(System.currentTimeMillis());
        task.setUpdatedBy(currentUserId());
        taskMapper.updateById(task);
    }

    // ==================== 评论管理 ====================

    public List<RequirementCommentDTO> getComments(String requirementId) {
        return commentMapper.selectList(
                new LambdaQueryWrapper<RequirementComment>()
                        .eq(RequirementComment::getRequirementId, requirementId)
                        .eq(RequirementComment::getDeleted, 0)
                        .orderByDesc(RequirementComment::getCreatedAt))
                .stream().map(this::toCommentDTO).toList();
    }

    @Transactional
    public RequirementCommentDTO createComment(String requirementId, CreateCommentCommand command) {
        getOrThrow(requirementId);
        RequirementComment comment = new RequirementComment();
        comment.setId(ULIDUtil.next());
        comment.setRequirementId(requirementId);
        comment.setContent(command.getContent());
        comment.setParentId(command.getParentId());
        comment.setAttachments(command.getAttachments());
        comment.setCreatedAt(System.currentTimeMillis());
        comment.setUpdatedAt(System.currentTimeMillis());
        comment.setCreatedBy(currentUserId());
        comment.setUpdatedBy(currentUserId());
        commentMapper.insert(comment);
        return toCommentDTO(comment);
    }

    @Transactional
    public void deleteComment(String commentId) {
        RequirementComment comment = commentMapper.selectById(commentId);
        if (comment == null) throw BusinessException.of(ResultCode.REQUIREMENT_COMMENT_NOT_FOUND);
        comment.setDeleted(1);
        comment.setUpdatedAt(System.currentTimeMillis());
        comment.setUpdatedBy(currentUserId());
        commentMapper.updateById(comment);
    }

    // ==================== 统计 ====================

    public RequirementStatsDTO getStats() {
        List<Requirement> all = requirementMapper.selectList(
                new LambdaQueryWrapper<Requirement>().eq(Requirement::getDeleted, 0));

        Map<String, Long> statusCount = new HashMap<>();
        Map<String, Long> typeCount = new HashMap<>();
        Map<String, Long> priorityCount = new HashMap<>();

        for (Requirement req : all) {
            statusCount.merge(req.getStatus(), 1L, Long::sum);
            typeCount.merge(req.getType(), 1L, Long::sum);
            priorityCount.merge("P" + req.getPriority(), 1L, Long::sum);
        }

        return RequirementStatsDTO.builder()
                .statusCount(statusCount)
                .typeCount(typeCount)
                .priorityCount(priorityCount)
                .totalCount((long) all.size())
                .build();
    }

    // ==================== 内部方法 ====================

    private LambdaQueryWrapper<Requirement> buildQueryWrapper(RequirementQuery query) {
        LambdaQueryWrapper<Requirement> wrapper = new LambdaQueryWrapper<Requirement>()
                .eq(Requirement::getDeleted, 0);

        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.and(w -> w.like(Requirement::getTitle, query.getKeyword())
                    .or().like(Requirement::getRequirementCode, query.getKeyword()));
        }
        if (query.getType() != null) wrapper.eq(Requirement::getType, query.getType());
        if (query.getPriority() != null) wrapper.eq(Requirement::getPriority, query.getPriority());
        if (query.getStatus() != null) wrapper.eq(Requirement::getStatus, query.getStatus());
        if (query.getAssigneeId() != null) wrapper.eq(Requirement::getAssigneeId, query.getAssigneeId());
        if (query.getProjectId() != null) wrapper.eq(Requirement::getProjectId, query.getProjectId());

        wrapper.orderByDesc(Requirement::getCreatedAt);
        return wrapper;
    }

    private void validateStatusTransition(String currentStatus, String newStatus) {
        // 简化版状态流转校验，实际可根据业务规则细化
        if (currentStatus.equals(newStatus)) return;

        // 已归档和已取消的需求不能再变更状态
        if (RequirementStatus.ARCHIVED.getCode().equals(currentStatus)
                || RequirementStatus.CANCELLED.getCode().equals(currentStatus)) {
            throw BusinessException.of(ResultCode.REQUIREMENT_STATUS_INVALID, "终态需求不可变更状态");
        }
    }

    private void checkEditable(Requirement req) {
        if (req.getDeleted() != null && req.getDeleted() == 1) {
            throw BusinessException.of(ResultCode.REQUIREMENT_NOT_FOUND);
        }
        // 只有特定状态允许编辑核心字段
        RequirementStatus status;
        try {
            status = RequirementStatus.valueOf(req.getStatus());
        } catch (Exception e) {
            return;
        }
        if (!status.isEditable()) {
            throw BusinessException.of(ResultCode.REQUIREMENT_STATUS_INVALID,
                    "当前状态【" + status.getDisplayName() + "】不允许编辑核心字段");
        }
    }

    private String generateRequirementCode() {
        String year = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy"));
        AtomicInteger counter = CODE_COUNTERS.computeIfAbsent(year, k -> new AtomicInteger(0));
        int seq = counter.incrementAndGet();
        return String.format("REQ-%s-%03d", year, seq);
    }

    private String generateTaskCode() {
        String year = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy"));
        String key = "task-" + year;
        AtomicInteger counter = CODE_COUNTERS.computeIfAbsent(key, k -> new AtomicInteger(0));
        int seq = counter.incrementAndGet();
        return String.format("TASK-%s-%03d", year, seq);
    }

    private Requirement getOrThrow(String id) {
        Requirement req = requirementMapper.selectById(id);
        if (req == null || (req.getDeleted() != null && req.getDeleted() == 1)) {
            throw BusinessException.of(ResultCode.REQUIREMENT_NOT_FOUND);
        }
        return req;
    }

    private RequirementTask getTaskOrThrow(String id) {
        RequirementTask task = taskMapper.selectById(id);
        if (task == null || (task.getDeleted() != null && task.getDeleted() == 1)) {
            throw BusinessException.of(ResultCode.REQUIREMENT_TASK_NOT_FOUND);
        }
        return task;
    }

    private String currentUserId() {
        String userId = UserContext.currentUserId();
        if (userId == null) throw BusinessException.of(ResultCode.UNAUTHORIZED);
        return userId;
    }

    private RequirementDTO toDTO(Requirement req) {
        RequirementType type = null;
        try { type = RequirementType.valueOf(req.getType()); } catch (Exception ignored) {}
        RequirementStatus status = null;
        try { status = RequirementStatus.valueOf(req.getStatus()); } catch (Exception ignored) {}

        UserAccount reporter = req.getReporterId() != null ? userAccountMapper.selectById(req.getReporterId()) : null;
        UserAccount assignee = req.getAssigneeId() != null ? userAccountMapper.selectById(req.getAssigneeId()) : null;

        return RequirementDTO.builder()
                .id(req.getId())
                .requirementCode(req.getRequirementCode())
                .title(req.getTitle())
                .description(req.getDescription())
                .type(req.getType())
                .typeDesc(type != null ? type.getDisplayName() : req.getType())
                .typeIcon(type != null ? type.getIcon() : "")
                .priority(req.getPriority())
                .priorityDesc(req.getPriority() != null ? "P" + req.getPriority() : "")
                .status(req.getStatus())
                .statusDesc(status != null ? status.getDisplayName() : req.getStatus())
                .assigneeId(req.getAssigneeId())
                .assigneeName(assignee != null ? assignee.getDisplayName() : null)
                .reporterId(req.getReporterId())
                .reporterName(reporter != null ? reporter.getDisplayName() : null)
                .designerId(req.getDesignerId())
                .developerId(req.getDeveloperId())
                .testerId(req.getTesterId())
                .parentId(req.getParentId())
                .projectId(req.getProjectId())
                .sessionIds(req.getSessionIds())
                .documentIds(req.getDocumentIds())
                .designDoc(req.getDesignDoc())
                .planDoc(req.getPlanDoc())
                .testDoc(req.getTestDoc())
                .storyPoint(req.getStoryPoint())
                .estimatedHours(req.getEstimatedHours())
                .actualHours(req.getActualHours())
                .dueDate(req.getDueDate())
                .startedAt(req.getStartedAt())
                .completedAt(req.getCompletedAt())
                .releasedAt(req.getReleasedAt())
                .cancelledAt(req.getCancelledAt())
                .cancelReason(req.getCancelReason())
                .gitBranch(req.getGitBranch())
                .createdAt(req.getCreatedAt())
                .createdBy(req.getCreatedBy())
                .build();
    }

    private RequirementReviewDTO toReviewDTO(RequirementReview review) {
        ReviewType rt = null;
        try { rt = ReviewType.valueOf(review.getReviewType()); } catch (Exception ignored) {}
        UserAccount reviewer = review.getReviewerId() != null ? userAccountMapper.selectById(review.getReviewerId()) : null;
        return RequirementReviewDTO.builder()
                .id(review.getId())
                .requirementId(review.getRequirementId())
                .reviewType(review.getReviewType())
                .reviewTypeDesc(rt != null ? rt.getDisplayName() : review.getReviewType())
                .reviewerId(review.getReviewerId())
                .reviewerName(reviewer != null ? reviewer.getDisplayName() : null)
                .reviewResult(review.getReviewResult())
                .reviewResultDesc(review.getReviewResult())
                .reviewComment(review.getReviewComment())
                .reviewAttachments(review.getReviewAttachments())
                .reviewOrder(review.getReviewOrder())
                .completedAt(review.getCompletedAt())
                .createdAt(review.getCreatedAt())
                .createdBy(review.getCreatedBy())
                .build();
    }

    private RequirementTaskDTO toTaskDTO(RequirementTask task) {
        UserAccount assignee = task.getAssigneeId() != null ? userAccountMapper.selectById(task.getAssigneeId()) : null;
        return RequirementTaskDTO.builder()
                .id(task.getId())
                .requirementId(task.getRequirementId())
                .taskCode(task.getTaskCode())
                .title(task.getTitle())
                .description(task.getDescription())
                .assigneeId(task.getAssigneeId())
                .assigneeName(assignee != null ? assignee.getDisplayName() : null)
                .status(task.getStatus())
                .statusDesc(task.getStatus())
                .estimatedHours(task.getEstimatedHours())
                .actualHours(task.getActualHours())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .createdAt(task.getCreatedAt())
                .createdBy(task.getCreatedBy())
                .build();
    }

    private RequirementCommentDTO toCommentDTO(RequirementComment comment) {
        UserAccount creator = comment.getCreatedBy() != null ? userAccountMapper.selectById(comment.getCreatedBy()) : null;
        return RequirementCommentDTO.builder()
                .id(comment.getId())
                .requirementId(comment.getRequirementId())
                .content(comment.getContent())
                .parentId(comment.getParentId())
                .attachments(comment.getAttachments())
                .createdAt(comment.getCreatedAt())
                .createdBy(comment.getCreatedBy())
                .createdByName(creator != null ? creator.getDisplayName() : null)
                .build();
    }
}
