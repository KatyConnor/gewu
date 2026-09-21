package com.gewu.application.requirement;

import com.gewu.application.requirement.dto.*;
import com.gewu.common.context.UserContext;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.requirement.*;
import com.gewu.domain.user.UserAccount;
import com.gewu.infrastructure.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequirementServiceTest {

    @Mock
    private RequirementMapper requirementMapper;

    @Mock
    private RequirementReviewMapper reviewMapper;

    @Mock
    private RequirementTaskMapper taskMapper;

    @Mock
    private RequirementCommentMapper commentMapper;

    @Mock
    private UserAccountMapper userAccountMapper;

    @Mock
    private RequirementFileMapper requirementFileMapper;

    @Mock
    private com.gewu.infrastructure.storage.MinioStorageService storageService;

    @InjectMocks
    private RequirementService requirementService;

    private Requirement testRequirement;
    private UserAccount testUser;

    @BeforeEach
    void setUp() {
        // 模拟用户上下文
        UserContext.set(UserContext.builder()
            .userId("test-user-id")
            .username("testuser")
            .displayName("测试用户")
            .permissions(java.util.Set.of(
                "requirement:create",
                "requirement:edit",
                "requirement:delete",
                "requirement:review:submit",
                "review:execute",
                "task:create",
                "task:edit",
                "task:delete"
            ))
            .roleCodes(java.util.List.of("PRODUCT_MANAGER"))
            .build());

        // 创建测试用户
        testUser = new UserAccount();
        testUser.setId("test-user-id");
        testUser.setDisplayName("测试用户");

        // 创建测试需求
        testRequirement = new Requirement();
        testRequirement.setId("test-req-id");
        testRequirement.setRequirementCode("REQ-2026-001");
        testRequirement.setTitle("测试需求");
        testRequirement.setDescription("这是一个测试需求");
        testRequirement.setType("FEATURE");
        testRequirement.setPriority(1);
        testRequirement.setStatus("DRAFT");
        testRequirement.setReporterId("test-user-id");
        testRequirement.setCreatedAt(System.currentTimeMillis());
        testRequirement.setUpdatedAt(System.currentTimeMillis());
        testRequirement.setCreatedBy("test-user-id");
        testRequirement.setUpdatedBy("test-user-id");
        testRequirement.setDeleted(0);
    }

    // ==================== 创建需求测试 ====================

    @Test
    void createRequirement_Success() {
        // 准备
        CreateRequirementCommand command = new CreateRequirementCommand();
        command.setTitle("新需求");
        command.setType("STORY");
        command.setPriority(2);
        command.setDescription("需求描述");

        when(requirementMapper.insert(any(Requirement.class))).thenReturn(1);

        // 执行
        RequirementDTO result = requirementService.createRequirement(command);

        // 验证
        assertNotNull(result);
        assertEquals("新需求", result.getTitle());
        assertEquals("STORY", result.getType());
        assertEquals(2, result.getPriority());
        assertEquals("DRAFT", result.getStatus());
        verify(requirementMapper, times(1)).insert(any(Requirement.class));
    }

    @Test
    void createRequirement_WithNullUserId_ThrowsException() {
        // 准备
        UserContext.clear();
        CreateRequirementCommand command = new CreateRequirementCommand();
        command.setTitle("新需求");

        // 执行 & 验证
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            requirementService.createRequirement(command);
        });

        assertEquals(ResultCode.UNAUTHORIZED.getCode(), exception.getCode());
    }

    // ==================== 更新需求测试 ====================

    @Test
    void updateRequirement_Success() {
        // 准备
        when(requirementMapper.selectById("test-req-id")).thenReturn(testRequirement);
        when(requirementMapper.updateById(any(Requirement.class))).thenReturn(1);
        when(userAccountMapper.selectById(anyString())).thenReturn(testUser);

        UpdateRequirementCommand command = new UpdateRequirementCommand();
        command.setTitle("更新后的需求");
        command.setDescription("更新后的描述");

        // 执行
        RequirementDTO result = requirementService.updateRequirement("test-req-id", command);

        // 验证
        assertNotNull(result);
        assertEquals("更新后的需求", result.getTitle());
        verify(requirementMapper, times(1)).updateById(any(Requirement.class));
    }

    @Test
    void updateRequirement_NotFound_ThrowsException() {
        // 准备
        when(requirementMapper.selectById("non-existent-id")).thenReturn(null);

        UpdateRequirementCommand command = new UpdateRequirementCommand();
        command.setTitle("更新");

        // 执行 & 验证
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            requirementService.updateRequirement("non-existent-id", command);
        });

        assertEquals(ResultCode.REQUIREMENT_NOT_FOUND.getCode(), exception.getCode());
    }

    @Test
    void updateRequirement_NonEditableStatus_ThrowsException() {
        // 准备 - 设置为不可编辑状态
        testRequirement.setStatus("IN_DEV");
        when(requirementMapper.selectById("test-req-id")).thenReturn(testRequirement);

        UpdateRequirementCommand command = new UpdateRequirementCommand();
        command.setTitle("更新");

        // 执行 & 验证
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            requirementService.updateRequirement("test-req-id", command);
        });

        assertEquals(ResultCode.REQUIREMENT_STATUS_INVALID.getCode(), exception.getCode());
    }

    // ==================== 删除需求测试 ====================

    @Test
    void deleteRequirement_Success() {
        // 准备
        when(requirementMapper.selectById("test-req-id")).thenReturn(testRequirement);
        when(requirementMapper.updateById(any(Requirement.class))).thenReturn(1);
        when(requirementFileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        // 执行
        requirementService.deleteRequirement("test-req-id");

        // 验证
        verify(requirementMapper, times(1)).updateById(argThat(req -> req.getDeleted() == 1));
    }

    // ==================== 状态流转测试 ====================

    @Test
    void updateStatus_DraftToPendingReview_Success() {
        // 准备
        when(requirementMapper.selectById("test-req-id")).thenReturn(testRequirement);
        when(requirementMapper.updateById(any(Requirement.class))).thenReturn(1);

        UpdateRequirementStatusCommand command = new UpdateRequirementStatusCommand();
        command.setStatus("PENDING_REVIEW");

        // 执行
        RequirementDTO result = requirementService.updateStatus("test-req-id", command);

        // 验证
        assertEquals("PENDING_REVIEW", result.getStatus());
        verify(requirementMapper, times(1)).updateById(any(Requirement.class));
    }

    @Test
    void updateStatus_Archived_ThrowsException() {
        // 准备 - 设置为已归档状态
        testRequirement.setStatus("ARCHIVED");
        when(requirementMapper.selectById("test-req-id")).thenReturn(testRequirement);

        UpdateRequirementStatusCommand command = new UpdateRequirementStatusCommand();
        command.setStatus("DRAFT");

        // 执行 & 验证
        assertThrows(BusinessException.class, () -> {
            requirementService.updateStatus("test-req-id", command);
        });
    }

    @Test
    void updateStatus_Released_RecordsTimestamps() {
        // 准备
        testRequirement.setStatus("UAT_TEST");
        when(requirementMapper.selectById("test-req-id")).thenReturn(testRequirement);
        when(requirementMapper.updateById(any(Requirement.class))).thenReturn(1);

        UpdateRequirementStatusCommand command = new UpdateRequirementStatusCommand();
        command.setStatus("RELEASED");

        // 执行
        RequirementDTO result = requirementService.updateStatus("test-req-id", command);

        // 验证
        assertEquals("RELEASED", result.getStatus());
        assertNotNull(result.getReleasedAt());
        assertNotNull(result.getCompletedAt());
    }

    // ==================== 评审管理测试 ====================

    @Test
    void submitReview_Success() {
        // 准备
        when(requirementMapper.selectById("test-req-id")).thenReturn(testRequirement);
        when(requirementMapper.updateById(any(Requirement.class))).thenReturn(1);

        SubmitReviewCommand command = new SubmitReviewCommand();
        command.setReviewType("REQUIREMENT");

        // 执行
        requirementService.submitReview("test-req-id", command);

        // 验证
        verify(requirementMapper, times(1)).updateById(argThat(req ->
            "PENDING_REVIEW".equals(req.getStatus())
        ));
    }

    @Test
    void submitReview_WrongStatus_ThrowsException() {
        // 准备
        testRequirement.setStatus("IN_DEV");
        when(requirementMapper.selectById("test-req-id")).thenReturn(testRequirement);

        SubmitReviewCommand command = new SubmitReviewCommand();
        command.setReviewType("REQUIREMENT");

        // 执行 & 验证
        assertThrows(BusinessException.class, () -> {
            requirementService.submitReview("test-req-id", command);
        });
    }

    @Test
    void submitReviewOpinion_Approved_Success() {
        // 准备
        when(requirementMapper.selectById("test-req-id")).thenReturn(testRequirement);
        when(reviewMapper.insert(any(RequirementReview.class))).thenReturn(1);
        when(requirementMapper.updateById(any(Requirement.class))).thenReturn(1);

        SubmitReviewOpinionCommand command = new SubmitReviewOpinionCommand();
        command.setReviewType("REQUIREMENT");
        command.setReviewResult("APPROVED");
        command.setReviewComment("评审通过");

        // 执行
        requirementService.submitReviewOpinion("test-req-id", command);

        // 验证
        verify(reviewMapper, times(1)).insert(any(RequirementReview.class));
        verify(requirementMapper, times(1)).updateById(argThat(req ->
            "APPROVED".equals(req.getStatus())
        ));
    }

    @Test
    void submitReviewOpinion_Rejected_ReturnsToDraft() {
        // 准备
        when(requirementMapper.selectById("test-req-id")).thenReturn(testRequirement);
        when(reviewMapper.insert(any(RequirementReview.class))).thenReturn(1);
        when(requirementMapper.updateById(any(Requirement.class))).thenReturn(1);

        SubmitReviewOpinionCommand command = new SubmitReviewOpinionCommand();
        command.setReviewType("REQUIREMENT");
        command.setReviewResult("REJECTED");
        command.setReviewComment("需要修改");

        // 执行
        requirementService.submitReviewOpinion("test-req-id", command);

        // 验证
        verify(requirementMapper, times(1)).updateById(argThat(req ->
            "DRAFT".equals(req.getStatus())
        ));
    }

    // ==================== 任务管理测试 ====================

    @Test
    void createTask_Success() {
        // 准备
        when(requirementMapper.selectById("test-req-id")).thenReturn(testRequirement);
        when(taskMapper.insert(any(RequirementTask.class))).thenReturn(1);

        CreateTaskCommand command = new CreateTaskCommand();
        command.setTitle("开发任务");
        command.setEstimatedHours(8);

        // 执行
        RequirementTaskDTO result = requirementService.createTask("test-req-id", command);

        // 验证
        assertNotNull(result);
        assertEquals("开发任务", result.getTitle());
        assertEquals("PENDING", result.getStatus());
        verify(taskMapper, times(1)).insert(any(RequirementTask.class));
    }

    @Test
    void updateTask_ToInProgress_SetsStartedAt() {
        // 准备
        RequirementTask task = new RequirementTask();
        task.setId("task-id");
        task.setRequirementId("test-req-id");
        task.setTitle("任务");
        task.setStatus("PENDING");
        when(taskMapper.selectById("task-id")).thenReturn(task);
        when(taskMapper.updateById(any(RequirementTask.class))).thenReturn(1);

        UpdateTaskCommand command = new UpdateTaskCommand();
        command.setStatus("IN_PROGRESS");

        // 执行
        requirementService.updateTask("task-id", command);

        // 验证
        verify(taskMapper, times(1)).updateById(argThat(t ->
            "IN_PROGRESS".equals(t.getStatus()) && t.getStartedAt() != null
        ));
    }

    // ==================== 评论管理测试 ====================

    @Test
    void createComment_Success() {
        // 准备
        when(requirementMapper.selectById("test-req-id")).thenReturn(testRequirement);
        when(commentMapper.insert(any(RequirementComment.class))).thenReturn(1);
        when(userAccountMapper.selectById(anyString())).thenReturn(testUser);

        CreateCommentCommand command = new CreateCommentCommand();
        command.setContent("这是一条评论");

        // 执行
        RequirementCommentDTO result = requirementService.createComment("test-req-id", command);

        // 验证
        assertNotNull(result);
        assertEquals("这是一条评论", result.getContent());
        verify(commentMapper, times(1)).insert(any(RequirementComment.class));
    }

    // ==================== 查询测试 ====================

    @Test
    void listRequirements_WithFilters_Success() {
        // 准备
        RequirementQuery query = new RequirementQuery();
        query.setPage(1);
        query.setSize(20);
        query.setType("FEATURE");
        query.setPriority(1);

        Page<Requirement> page = new Page<>(1, 20);
        page.setRecords(List.of(testRequirement));
        page.setTotal(1);

        when(requirementMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);
        when(userAccountMapper.selectById(anyString())).thenReturn(testUser);

        // 执行
        PageResult<RequirementDTO> result = requirementService.listRequirements(query);

        // 验证
        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals(1, result.getTotal());
    }

    @Test
    void getRequirement_NotFound_ThrowsException() {
        // 准备
        when(requirementMapper.selectById("non-existent")).thenReturn(null);

        // 执行 & 验证
        assertThrows(BusinessException.class, () -> {
            requirementService.getRequirement("non-existent");
        });
    }

    @Test
    void getStats_Success() {
        // 准备
        when(requirementMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(testRequirement));

        // 执行
        RequirementStatsDTO stats = requirementService.getStats();

        // 验证
        assertNotNull(stats);
        assertEquals(1L, stats.getTotalCount());
        assertEquals(1L, stats.getStatusCount().get("DRAFT"));
        assertEquals(1L, stats.getTypeCount().get("FEATURE"));
    }
}
