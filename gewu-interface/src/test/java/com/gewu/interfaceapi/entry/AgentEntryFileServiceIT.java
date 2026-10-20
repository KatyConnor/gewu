package com.gewu.interfaceapi.entry;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.gewu.application.entry.AgentEntryFileService;
import com.gewu.application.sandbox.SandboxClient;
import com.gewu.application.workspace.WorkspaceSyncService;
import com.gewu.agent.engine.core.AgentTask;
import com.gewu.domain.project.Project;
import com.gewu.domain.session.Session;
import com.gewu.domain.workspace.Workspace;
import com.gewu.domain.workspace.WorkspaceFile;
import com.gewu.domain.workspace.WorkspaceSyncState;
import com.gewu.infrastructure.mapper.ProjectMapper;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.WorkspaceFileMapper;
import com.gewu.infrastructure.mapper.WorkspaceMapper;
import com.gewu.infrastructure.storage.MinioStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentEntryFileService} 平台集成测试（设计文档 56 §11.2）：
 * 最小 Spring 上下文（H2 + 真实 Mapper + mock MinIO/沙箱/同步服务），
 * 覆盖四条集成口径——MinIO 全局单飞、沙箱离线降级、clone 后仓库自带文件生效
 * （无版本头不被覆盖·评审质询核心语义）、PUT 全局建行+入队+读回。
 */
@SpringBootTest(classes = AgentEntryFileServiceIT.Boot.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:entry_it;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema.sql",
        "mybatis-plus.configuration.map-underscore-to-camel-case=true",
        "mybatis-plus.global-config.db-config.id-type=input",
        "gewu.entry.max-bytes=32768",
})
class AgentEntryFileServiceIT {

    @SpringBootConfiguration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, MybatisPlusAutoConfiguration.class,
            JacksonAutoConfiguration.class, SqlInitializationAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration.class,
            org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration.class})
    @MapperScan("com.gewu.infrastructure.mapper")
    @ComponentScan(basePackageClasses = AgentEntryFileService.class)
    static class Boot {}

    @MockBean SandboxClient sandboxClient;
    @MockBean MinioStorageService storageService;
    @MockBean WorkspaceSyncService syncService;

    @Autowired AgentEntryFileService service;
    @Autowired WorkspaceMapper workspaceMapper;
    @Autowired WorkspaceFileMapper fileMapper;
    @Autowired SessionMapper sessionMapper;
    @Autowired ProjectMapper projectMapper;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    // ==================== 夹具 ====================

    private void insertWorkspace(String userId, String storagePath, String devSandboxId) {
        Workspace ws = new Workspace();
        ws.setId("ws-" + userId);
        ws.setUserId(userId);
        ws.setStoragePath(storagePath);
        ws.setMode("dev");
        ws.setDevSandboxId(devSandboxId);
        ws.setDeleted(0);
        ws.setCreatedAt(System.currentTimeMillis());
        ws.setUpdatedAt(System.currentTimeMillis());
        ws.setCreatedBy(userId);
        workspaceMapper.insert(ws);
    }

    private void insertSession(String sessionId, String directory, String projectId) {
        Session s = new Session();
        s.setId(sessionId);
        s.setDirectory(directory);
        s.setProjectId(projectId);
        s.setTitle("it");
        s.setDeleted(0);
        s.setCreatedAt(System.currentTimeMillis());
        s.setUpdatedAt(System.currentTimeMillis());
        s.setCreatedBy("u-off");
        sessionMapper.insert(s);
    }

    private void insertProject(String projectId, String ownerId, String repoLocalPath) {
        Project p = new Project();
        p.setId(projectId);
        p.setProjectName("IT项目");
        p.setProjectCode("IT-001");
        p.setOwnerId(ownerId);
        p.setRepoLocalPath(repoLocalPath);
        p.setDeleted(0);
        p.setCreatedAt(System.currentTimeMillis());
        p.setUpdatedAt(System.currentTimeMillis());
        p.setCreatedBy(ownerId);
        projectMapper.insert(p);
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("MinIO 全局单飞：无项目层时全局文件生效（only-candidate，不依赖沙箱）")
    void globalOnly_resolvesFromMinio() {
        insertWorkspace("u-g1", "workspaces/u-g1/", null);
        when(storageService.getContent("workspaces/u-g1/GEWU.md"))
                .thenReturn("---\nversion: 1.1.0\n---\n全局规范内容");

        AgentEntryFileService.EntryView view = service.resolve("u-g1", null);

        assertThat(view.present()).isTrue();
        assertThat(view.source()).isEqualTo("user-global:GEWU.md");
        assertThat(view.content()).contains("全局规范内容");
        assertThat(view.metaJson()).contains("\"reason\":\"only-candidate\"");
    }

    @Test
    @DisplayName("沙箱离线降级：devSandboxId 为空时项目层跳过，仅全局级生效")
    void sandboxOffline_projectLayerSkipped() {
        insertWorkspace("u-off", "workspaces/u-off/", null);
        insertProject("p-off", "u-off", "projects/p-off/repo");
        insertSession("s-off", "/workspace/projects/p-off/repo", "p-off");
        when(storageService.getContent("workspaces/u-off/GEWU.md")).thenReturn("全局规范");

        AgentEntryFileService.EntryView view = service.resolve("u-off", "projects/p-off/repo");
        assertThat(view.source()).isEqualTo("user-global:GEWU.md");
        assertThat(view.metaJson()).contains("\"reason\":\"only-candidate\"");

        // 引擎注入链路：applyToTask 经会话目录解析并填充任务字段
        AgentTask task = AgentTask.builder().userId("u-off").sessionId("s-off").message("hi").build();
        service.applyToTask(task);
        assertThat(task.getEntryDirectives()).contains("全局规范");
        assertThat(task.getEntryDirectivesSource()).isEqualTo("user-global:GEWU.md");
    }

    @Test
    @DisplayName("仓库自带文件生效：无版本头项目 AGENTS.md 不被带版本全局静默覆盖（评审质询核心语义）")
    void repoSuppliedUnversioned_wins_projectDefault() {
        insertWorkspace("u-proj", "workspaces/u-proj/", "sb-proj");
        when(storageService.getContent("workspaces/u-proj/GEWU.md"))
                .thenReturn("---\nversion: 9.9.9\n---\n全局模板");
        when(sandboxClient.readFile("sb-proj", "projects/p-proj/repo/AGENTS.md"))
                .thenReturn("# 从开源仓库导入的外部规范");

        AgentEntryFileService.EntryView view = service.resolve("u-proj", "projects/p-proj/repo");

        assertThat(view.source()).isEqualTo("project:AGENTS.md");
        assertThat(view.content()).contains("从开源仓库导入");
        assertThat(view.metaJson()).contains("\"reason\":\"project-default\"");
    }

    @Test
    @DisplayName("双方声明版本：版本高者胜（opt-in 协议）")
    void bothVersioned_higherWins() {
        insertWorkspace("u-both", "workspaces/u-both/", "sb-both");
        when(storageService.getContent("workspaces/u-both/GEWU.md"))
                .thenReturn("---\nversion: 1.1.0\n---\n全局治理模板");
        when(sandboxClient.readFile("sb-both", "projects/p-both/repo/GEWU.md"))
                .thenReturn("---\nversion: 2.0.0\n---\n项目自有规范");

        AgentEntryFileService.EntryView view = service.resolve("u-both", "projects/p-both/repo");

        assertThat(view.source()).isEqualTo("project:GEWU.md");
        assertThat(view.metaJson()).contains("\"version\":\"2.0.0\"");
        assertThat(view.metaJson()).contains("\"reason\":\"version-higher\"");
    }

    @Test
    @DisplayName("PUT 全局：workspace_file 建行 + M2V 入队 + 读回生效（评审 F-202 闭环）")
    void saveGlobal_registersRowEnqueuesAndReadsBack() {
        insertWorkspace("u-save", "workspaces/u-save/", null);

        // 显式事务包裹：验证服务落库逻辑（行构造/入队/读回），
        // 与最小上下文的事务代理语义解耦
        transactionTemplate.executeWithoutResult(status ->
                service.saveGlobalFile("u-save", "GEWU.md", "# 保存后的全局规范"));

        // 台账行落库
        WorkspaceFile row = fileMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkspaceFile>()
                .eq(WorkspaceFile::getWorkspaceId, "ws-u-save")
                .eq(WorkspaceFile::getFilePath, "GEWU.md"));
        assertThat(row).isNotNull();
        assertThat(row.getObjectKey()).isEqualTo("workspaces/u-save/GEWU.md");
        // M2V 入队（幂等种子语义：沙箱后建由对账自愈）
        verify(syncService).enqueue("ws-u-save", "GEWU.md", WorkspaceSyncState.DIRECTION_M2V);

        // 读回与裁决生效
        when(storageService.getContent("workspaces/u-save/GEWU.md")).thenReturn("# 保存后的全局规范");
        assertThat(service.readGlobalFile("u-save", "GEWU.md")).isEqualTo("# 保存后的全局规范");
        AgentEntryFileService.EntryView view = service.resolve("u-save", null);
        assertThat(view.content()).contains("保存后的全局规范");
    }
}
