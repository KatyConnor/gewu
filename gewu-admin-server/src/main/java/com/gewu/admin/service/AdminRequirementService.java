package com.gewu.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.admin.dto.requirement.AdminRequirementCreateCommand;
import com.gewu.admin.dto.requirement.AdminRequirementDTO;
import com.gewu.admin.dto.requirement.AdminRequirementStatusCommand;
import com.gewu.admin.dto.requirement.AdminRequirementUpdateCommand;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.enums.RequirementStatus;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.project.Project;
import com.gewu.domain.project.ProjectMember;
import com.gewu.domain.requirement.Requirement;
import com.gewu.domain.requirement.RequirementFile;
import com.gewu.domain.user.UserAccount;
import com.gewu.infrastructure.mapper.ProjectMapper;
import com.gewu.infrastructure.mapper.ProjectMemberMapper;
import com.gewu.infrastructure.mapper.RequirementFileMapper;
import com.gewu.infrastructure.mapper.RequirementMapper;
import com.gewu.infrastructure.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理端需求应用服务（需求管理迁移管理端：后台为需求唯一创建/编辑/分派入口）.
 *
 * <p>分派模型：requirement.assignee_id 即分派对象（用户侧"我的需求"= assignee=当前用户）；
 * 分派铁律 = 负责人必须是需求所绑定项目的 project_member 成员（OWNER/TECH_LEAD/MEMBER 任一），
 * 创建/编辑/更换绑定项目时强校验。
 * <p>需求编号：历史编号由用户侧内存计数器生成（重启重号），管理端改用 Redis 按年递增、
 * 首次访问时以存量当年最大序号校准，key 不设过期以保证跨年递增有序。
 * <p>与用户侧差异：删除需求的 MinIO 附件对象清理依赖用户侧运行时，管理端仅逻辑删
 * 需求行与需求文件行，对象清理由后续管理端联动补齐。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRequirementService {

    private static final String CODE_SEQ_KEY_PREFIX = "gewu:requirement:code:seq:";
    private static final Set<String> VALID_TYPES = Set.of("STORY", "TASK", "BUG", "IMPROVEMENT", "FEATURE", "EPIC");

    private final RequirementMapper requirementMapper;
    private final RequirementFileMapper requirementFileMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserAccountMapper userAccountMapper;
    private final StringRedisTemplate redisTemplate;

    // ==================== 查询 ====================

    public PageResult<AdminRequirementDTO> listRequirements(PageQuery query, String keyword, String type,
                                                            Integer priority, String status, String projectId) {
        Page<Requirement> page = new Page<>(query.getPage(), query.getSize());
        LambdaQueryWrapper<Requirement> wrapper = new LambdaQueryWrapper<Requirement>()
                .eq(Requirement::getDeleted, 0);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Requirement::getTitle, kw)
                    .or().like(Requirement::getRequirementCode, kw));
        }
        if (type != null && !type.isBlank()) wrapper.eq(Requirement::getType, type);
        if (priority != null) wrapper.eq(Requirement::getPriority, priority);
        if (status != null && !status.isBlank()) wrapper.eq(Requirement::getStatus, status);
        if (projectId != null && !projectId.isBlank()) wrapper.eq(Requirement::getProjectId, projectId);
        wrapper.orderByDesc(Requirement::getCreatedAt);

        Page<Requirement> result = requirementMapper.selectPage(page, wrapper);
        List<AdminRequirementDTO> dtos = toDTOs(result.getRecords());
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    public AdminRequirementDTO getRequirement(String id) {
        return toDTO(getOrThrow(id));
    }

    // ==================== 创建/编辑/分派 ====================

    @Transactional
    public AdminRequirementDTO createRequirement(AdminRequirementCreateCommand command) {
        if (command.getType() != null && !VALID_TYPES.contains(command.getType())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "需求类型不合法: " + command.getType());
        }
        Project project = projectMapper.selectById(command.getProjectId());
        if (project == null || (project.getDeleted() != null && project.getDeleted() == 1)) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "绑定的项目不存在");
        }
        checkAssigneeIsProjectMember(command.getProjectId(), command.getAssigneeId());

        Requirement req = new Requirement();
        req.setId(Ulid.next());
        req.setRequirementCode(generateRequirementCode());
        req.setTitle(command.getTitle().trim());
        req.setDescription(command.getDescription());
        req.setType(command.getType() != null ? command.getType() : "STORY");
        req.setPriority(command.getPriority() != null ? command.getPriority() : 2);
        req.setStatus(RequirementStatus.DRAFT.getCode());
        req.setReporterId(UserContext.currentUserId());
        req.setAssigneeId(command.getAssigneeId());
        req.setProjectId(command.getProjectId());
        req.setDueDate(command.getDueDate());
        req.setStoryPoint(command.getStoryPoint());
        req.setEstimatedHours(command.getEstimatedHours());
        long now = System.currentTimeMillis();
        req.setCreatedAt(now);
        req.setUpdatedAt(now);
        req.setCreatedBy(UserContext.currentUserId());
        req.setUpdatedBy(UserContext.currentUserId());
        requirementMapper.insert(req);
        return toDTO(req);
    }

    @Transactional
    public AdminRequirementDTO updateRequirement(String id, AdminRequirementUpdateCommand command) {
        Requirement req = getOrThrow(id);

        // 先确定最终绑定项目，再做分派校验（换项目后旧负责人可能不再是成员）
        String finalProjectId = command.getProjectId() != null ? command.getProjectId() : req.getProjectId();
        if (finalProjectId != null && !finalProjectId.equals(req.getProjectId())) {
            Project project = projectMapper.selectById(finalProjectId);
            if (project == null || (project.getDeleted() != null && project.getDeleted() == 1)) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "绑定的项目不存在");
            }
        }
        String finalAssigneeId = req.getAssigneeId();
        if (command.getAssigneeId() != null) {
            finalAssigneeId = command.getAssigneeId().isBlank() ? null : command.getAssigneeId();
        }
        checkAssigneeIsProjectMember(finalProjectId, finalAssigneeId);

        if (command.getTitle() != null && !command.getTitle().isBlank()) req.setTitle(command.getTitle().trim());
        if (command.getDescription() != null) req.setDescription(command.getDescription());
        if (command.getType() != null) {
            if (!VALID_TYPES.contains(command.getType())) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "需求类型不合法: " + command.getType());
            }
            req.setType(command.getType());
        }
        if (command.getPriority() != null) req.setPriority(command.getPriority());
        if (command.getProjectId() != null) req.setProjectId(command.getProjectId());
        req.setAssigneeId(finalAssigneeId);
        if (command.getDueDate() != null) req.setDueDate(command.getDueDate());
        if (command.getStoryPoint() != null) req.setStoryPoint(command.getStoryPoint());
        if (command.getEstimatedHours() != null) req.setEstimatedHours(command.getEstimatedHours());
        if (command.getDesignDoc() != null) req.setDesignDoc(command.getDesignDoc());
        if (command.getPlanDoc() != null) req.setPlanDoc(command.getPlanDoc());
        if (command.getTestDoc() != null) req.setTestDoc(command.getTestDoc());

        req.setUpdatedAt(System.currentTimeMillis());
        req.setUpdatedBy(UserContext.currentUserId());
        requirementMapper.updateById(req);
        return toDTO(req);
    }

    /** 分派铁律：负责人必须是绑定项目的 project_member 成员 */
    private void checkAssigneeIsProjectMember(String projectId, String assigneeId) {
        if (assigneeId == null || assigneeId.isBlank()) return;
        Long count = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, assigneeId));
        if (count == 0) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "分派对象必须是绑定项目的团队成员");
        }
    }

    // ==================== 状态流转 / 评审 / 删除 ====================

    @Transactional
    public AdminRequirementDTO updateStatus(String id, AdminRequirementStatusCommand command) {
        Requirement req = getOrThrow(id);
        String newStatus = command.getStatus();
        if (newStatus == null || newStatus.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "目标状态不能为空");
        }
        String current = req.getStatus();
        if (!current.equals(newStatus)) {
            if (RequirementStatus.ARCHIVED.getCode().equals(current)
                    || RequirementStatus.CANCELLED.getCode().equals(current)) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "终态需求不可变更状态");
            }
        }
        req.setStatus(newStatus);
        long now = System.currentTimeMillis();
        if (RequirementStatus.IN_DEV.getCode().equals(newStatus)) req.setStartedAt(now);
        if (RequirementStatus.RELEASED.getCode().equals(newStatus)) {
            req.setCompletedAt(now);
            req.setReleasedAt(now);
        }
        if (RequirementStatus.CANCELLED.getCode().equals(newStatus)) req.setCancelReason(command.getReason());
        req.setUpdatedAt(now);
        req.setUpdatedBy(UserContext.currentUserId());
        requirementMapper.updateById(req);
        return toDTO(req);
    }

    @Transactional
    public void submitReview(String id, String reviewType) {
        Requirement req = getOrThrow(id);
        if (!RequirementStatus.DRAFT.getCode().equals(req.getStatus())
                && !RequirementStatus.PENDING_REVIEW.getCode().equals(req.getStatus())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "只有草稿或待评审状态的需求可以提交评审");
        }
        req.setStatus(RequirementStatus.PENDING_REVIEW.getCode());
        req.setUpdatedAt(System.currentTimeMillis());
        req.setUpdatedBy(UserContext.currentUserId());
        requirementMapper.updateById(req);
        log.info("管理端提交需求评审: requirementId={}, reviewType={}", id, reviewType);
    }

    /**
     * 逻辑删除需求与需求文件行（@TableLogic）。
     * MinIO 附件对象清理依赖用户侧运行时，管理端暂不执行，见类注释。
     */
    @Transactional
    public void deleteRequirement(String id) {
        Requirement req = getOrThrow(id);
        req.setDeleted(1);
        req.setUpdatedAt(System.currentTimeMillis());
        req.setUpdatedBy(UserContext.currentUserId());
        requirementMapper.updateById(req);

        List<RequirementFile> files = requirementFileMapper.selectList(
                new LambdaQueryWrapper<RequirementFile>().eq(RequirementFile::getRequirementId, id));
        for (RequirementFile file : files) {
            requirementFileMapper.deleteById(file.getId());
        }
        log.info("管理端删除需求(逻辑删除): requirementId={}, title={}", id, req.getTitle());
    }

    // ==================== 内部辅助 ====================

    private Requirement getOrThrow(String id) {
        Requirement req = requirementMapper.selectById(id);
        if (req == null || (req.getDeleted() != null && req.getDeleted() == 1)) {
            throw BusinessException.of(ResultCode.REQUIREMENT_NOT_FOUND);
        }
        return req;
    }

    /** 需求编号：Redis 按年递增，首次以存量当年最大序号校准（历史编号源自用户侧内存计数器，重启重号） */
    private String generateRequirementCode() {
        String year = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy"));
        String key = CODE_SEQ_KEY_PREFIX + year;
        Long seq = redisTemplate.opsForValue().increment(key);
        if (seq != null && seq == 1L) {
            int max = queryMaxSeq(year);
            if (max > 0) {
                redisTemplate.opsForValue().set(key, String.valueOf(max));
                seq = redisTemplate.opsForValue().increment(key);
            }
        }
        if (seq == null) {
            log.warn("Redis 不可用，使用时间戳回退生成需求编号顺序号");
            seq = System.currentTimeMillis() % 100000L;
        }
        return String.format("REQ-%s-%03d", year, seq);
    }

    private int queryMaxSeq(String year) {
        List<Requirement> rows = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .likeRight(Requirement::getRequirementCode, "REQ-" + year + "-")
                .select(Requirement::getRequirementCode));
        int max = 0;
        for (Requirement r : rows) {
            String code = r.getRequirementCode();
            try {
                max = Math.max(max, Integer.parseInt(code.substring(code.lastIndexOf('-') + 1)));
            } catch (Exception ignored) {
                // 非标准编号不计入
            }
        }
        return max;
    }

    private List<AdminRequirementDTO> toDTOs(List<Requirement> requirements) {
        if (requirements == null || requirements.isEmpty()) return List.of();
        Set<String> userIds = new HashSet<>();
        Set<String> projectIds = new HashSet<>();
        for (Requirement r : requirements) {
            if (r.getAssigneeId() != null) userIds.add(r.getAssigneeId());
            if (r.getReporterId() != null) userIds.add(r.getReporterId());
            if (r.getProjectId() != null) projectIds.add(r.getProjectId());
        }
        Map<String, UserAccount> userMap = userIds.isEmpty() ? Map.of()
                : userAccountMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(UserAccount::getId, u -> u));
        Map<String, Project> projectMap = projectIds.isEmpty() ? Map.of()
                : projectMapper.selectBatchIds(projectIds).stream()
                        .collect(Collectors.toMap(Project::getId, p -> p));
        Map<String, String> projectNames = new HashMap<>();
        projectMap.forEach((id, p) -> projectNames.put(id, p.getProjectName()));

        return requirements.stream().map(r -> toDTO(r, userMap, projectNames)).toList();
    }

    private AdminRequirementDTO toDTO(Requirement r) {
        return toDTO(r, null, null);
    }

    private AdminRequirementDTO toDTO(Requirement r, Map<String, UserAccount> userMap, Map<String, String> projectNames) {
        UserAccount assignee = r.getAssigneeId() != null
                ? (userMap != null ? userMap.get(r.getAssigneeId()) : userAccountMapper.selectById(r.getAssigneeId()))
                : null;
        UserAccount reporter = r.getReporterId() != null
                ? (userMap != null ? userMap.get(r.getReporterId()) : userAccountMapper.selectById(r.getReporterId()))
                : null;
        String projectName = r.getProjectId() != null
                ? (projectNames != null ? projectNames.get(r.getProjectId()) : null)
                : null;

        return AdminRequirementDTO.builder()
                .id(r.getId())
                .requirementCode(r.getRequirementCode())
                .title(r.getTitle())
                .description(r.getDescription())
                .type(r.getType())
                .priority(r.getPriority())
                .status(r.getStatus())
                .assigneeId(r.getAssigneeId())
                .assigneeName(assignee != null ? assignee.getDisplayName() : null)
                .reporterId(r.getReporterId())
                .reporterName(reporter != null ? reporter.getDisplayName() : null)
                .projectId(r.getProjectId())
                .projectName(projectName)
                .dueDate(r.getDueDate())
                .storyPoint(r.getStoryPoint())
                .estimatedHours(r.getEstimatedHours())
                .createdAt(r.getCreatedAt())
                .startedAt(r.getStartedAt())
                .completedAt(r.getCompletedAt())
                .cancelReason(r.getCancelReason())
                .build();
    }
}
