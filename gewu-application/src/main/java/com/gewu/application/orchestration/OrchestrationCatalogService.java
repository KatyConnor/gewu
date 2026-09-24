package com.gewu.application.orchestration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.agent.engine.llm.model.ToolDefinition;
import com.gewu.agent.engine.orchestration.role.RoleRegistry;
import com.gewu.agent.engine.tool.ToolRegistry;
import com.gewu.domain.agent.AgentTool;
import com.gewu.infrastructure.mapper.AgentToolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 编排设计器目录服务 - 提供属性面板下拉可用的角色与工具目录（docs/design/46 报告 B3）。
 * <p>角色来自 {@link RoleRegistry}（内置 12 SDLC 角色 + SPI 扩展）；
 * 工具合并双源：代码级 {@link ToolRegistry}（优先）与 agent_tool 配置工具（status=1）。
 * 引擎 Bean 缺失时返回空目录，不阻断编排图管理。
 *
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
public class OrchestrationCatalogService {

    private static final int STATUS_ENABLED = 1;

    private final ObjectProvider<RoleRegistry> roleRegistryProvider;
    private final ObjectProvider<ToolRegistry> toolRegistryProvider;
    private final AgentToolMapper agentToolMapper;

    /** 角色目录项。 */
    public record RoleOption(String roleCode, String roleName, String sdlcPhase) {
    }

    /** 工具目录项：source=CODE 代码级工具 / CONFIG 配置工具。 */
    public record ToolOption(String name, String source, String description, String toolType) {
    }

    /**
     * 列出全部可用角色（按 roleCode 排序）。
     */
    public List<RoleOption> listRoles() {
        RoleRegistry registry = roleRegistryProvider.getIfAvailable();
        if (registry == null) {
            return List.of();
        }
        return registry.listAll().stream()
                .map(spec -> new RoleOption(spec.getRoleCode(), spec.getRoleName(), spec.getSdlcPhase()))
                .sorted(Comparator.comparing(RoleOption::roleCode))
                .toList();
    }

    /**
     * 列出全部可用工具：代码工具优先，同名配置工具去重。
     */
    public List<ToolOption> listTools() {
        List<ToolOption> result = new ArrayList<>();
        ToolRegistry registry = toolRegistryProvider.getIfAvailable();
        if (registry != null) {
            for (ToolDefinition definition : registry.listDefinitions()) {
                result.add(new ToolOption(definition.getName(), "CODE", definition.getDescription(), null));
            }
        }
        for (AgentTool tool : listEnabledConfigTools()) {
            boolean duplicated = result.stream().anyMatch(option -> option.name().equals(tool.getToolName()));
            if (!duplicated) {
                result.add(new ToolOption(tool.getToolName(), "CONFIG", tool.getDescription(), tool.getToolType()));
            }
        }
        return result;
    }

    private List<AgentTool> listEnabledConfigTools() {
        return agentToolMapper.selectList(new LambdaQueryWrapper<AgentTool>()
                .eq(AgentTool::getStatus, STATUS_ENABLED)
                .orderByAsc(AgentTool::getToolName));
    }
}
