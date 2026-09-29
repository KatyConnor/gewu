package com.veloflow.engine.definition;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.veloflow.engine.commons.VeloflowErrorCode;
import com.veloflow.engine.commons.VeloflowException;
import com.veloflow.engine.definition.dto.WorkflowPermissionDTO;
import com.veloflow.engine.identity.FlowIdentityProvider;
import com.veloflow.engine.persistence.mapper.WorkflowInstanceMapper;
import com.veloflow.engine.persistence.mapper.WorkflowMapper;
import com.veloflow.engine.persistence.mapper.WorkflowNodeMapper;
import com.veloflow.engine.persistence.mapper.WorkflowPermissionMapper;
import com.veloflow.engine.persistence.mapper.WorkflowPermissionMatrixMapper;
import com.veloflow.engine.persistence.mapper.WorkflowTransitionMapper;
import com.veloflow.engine.persistence.mapper.WorkflowVersionMapper;
import com.veloflow.engine.persistence.model.Workflow;
import com.veloflow.engine.persistence.model.WorkflowPermission;
import com.veloflow.engine.runtime.WorkflowDefinitionValidator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流程权限体系测试（权限体系接线）：发起权限（公开/命中/未命中）、
 * 管理权校验、整体替换。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowPermissionTest {

    @Mock WorkflowMapper workflowMapper;
    @Mock WorkflowNodeMapper workflowNodeMapper;
    @Mock WorkflowTransitionMapper workflowTransitionMapper;
    @Mock WorkflowInstanceMapper workflowInstanceMapper;
    @Mock WorkflowVersionMapper versionMapper;
    @Mock WorkflowPermissionMapper permissionMapper;
    @Mock WorkflowPermissionMatrixMapper permissionMatrixMapper;
    @Mock FlowIdentityProvider identityProvider;
    @Mock WorkflowDefinitionValidator definitionValidator;

    private WorkflowService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Workflow.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowPermission.class);
    }

    @BeforeEach
    void setUp() {
        service = new WorkflowService(workflowMapper, workflowNodeMapper, workflowTransitionMapper,
                workflowInstanceMapper, versionMapper, permissionMapper, permissionMatrixMapper,
                identityProvider, definitionValidator);
        lenient().when(identityProvider.currentRoles()).thenReturn(List.of("user"));
        lenient().when(workflowMapper.selectById("wf-1")).thenReturn(draft());
        lenient().when(permissionMapper.selectList(any())).thenReturn(List.of());
        lenient().when(permissionMatrixMapper.selectList(any())).thenReturn(List.of());
    }

    private Workflow draft() {
        Workflow wf = new Workflow();
        wf.setId("wf-1");
        wf.setWorkflowName("权限测试");
        wf.setStatus(1);
        wf.setWorkflowVersion(1);
        return wf;
    }

    private WorkflowPermission grant(String role, String type) {
        WorkflowPermission g = new WorkflowPermission();
        g.setWorkflowId("wf-1");
        g.setRoleCode(role);
        g.setPermissionType(type);
        return g;
    }

    @Test
    @DisplayName("未配置 START 权限=公开可发起（查询返回空集）")
    void noGrantsMeansPublic() {
        WorkflowPermissionDTO dto = service.getPermissions("wf-1");
        assertEquals(0, dto.getPermissions().size());
        assertEquals(0, dto.getMatrix().size());
    }

    @Test
    @DisplayName("updatePermissions：admin 角色整体替换（先删后插）")
    void adminCanReplacePermissions() {
        when(identityProvider.currentRoles()).thenReturn(List.of("admin"));

        WorkflowPermissionDTO command = WorkflowPermissionDTO.builder()
                .permissions(List.of(WorkflowPermissionDTO.PermissionGrant.builder()
                        .roleCode("manager").permissionType("start").build()))
                .matrix(List.of(WorkflowPermissionDTO.MatrixRule.builder()
                        .nodeType("approval").requiredRole("manager").permissionLevel("APPROVE").build()))
                .build();
        WorkflowPermissionDTO result = service.updatePermissions("wf-1", command);

        verify(permissionMapper).delete(any());
        verify(permissionMatrixMapper).delete(any());
        ArgumentCaptor<WorkflowPermission> captor = ArgumentCaptor.forClass(WorkflowPermission.class);
        verify(permissionMapper).insert(captor.capture());
        assertEquals("START", captor.getValue().getPermissionType(), "类型应归一大写");
    }

    @Test
    @DisplayName("管理权保护：非 admin、无 MANAGE 授权且已有授权集时拒绝")
    void nonAdminWithoutManageRejected() {
        when(permissionMapper.selectList(any())).thenReturn(List.of(grant("manager", "START")));

        VeloflowException ex = assertThrows(VeloflowException.class,
                () -> service.updatePermissions("wf-1", WorkflowPermissionDTO.builder().build()));
        assertEquals(VeloflowErrorCode.FLOW_UNAUTHORIZED, ex.getCode());
        verify(permissionMapper, never()).insert(any(WorkflowPermission.class));
    }

    @Test
    @DisplayName("MANAGE 授权角色可管理")
    void manageGrantHoldersCanManage() {
        when(permissionMapper.selectList(any())).thenReturn(List.of(grant("auditor", "MANAGE")));
        when(identityProvider.currentRoles()).thenReturn(List.of("auditor"));

        service.updatePermissions("wf-1", WorkflowPermissionDTO.builder().build());
        verify(permissionMapper).delete(any());
    }

    @Test
    @DisplayName("首次配置（权限集为空）任意登录用户可设置")
    void firstConfigurationAllowed() {
        when(permissionMapper.selectList(any())).thenReturn(List.of());

        service.updatePermissions("wf-1", WorkflowPermissionDTO.builder().build());
        verify(permissionMapper).delete(any());
    }
}
