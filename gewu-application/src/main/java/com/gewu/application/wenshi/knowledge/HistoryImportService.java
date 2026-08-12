package com.gewu.application.wenshi.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.domain.agent.AgentTool;
import com.gewu.infrastructure.mapper.AgentToolMapper;
import com.gewu.infrastructure.mapper.wenshi.EpisodicEventMapper;
import com.gewu.domain.wenshi.knowledge.EpisodicEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 历史数据导入服务 — 将存量会话消息和工具定义批量导入记忆系统。
 * <p>
 * 用于系统初始化或数据迁移场景：
 * <ul>
 *   <li>将历史会话消息导入情景记忆（{@link #importSessionMessages}）</li>
 *   <li>将已有工具定义导入程序记忆（{@link #importAllTools}）</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryImportService {

    private final EpisodicEventMapper episodicEventMapper;
    private final AgentToolMapper agentToolMapper;

    /**
     * 批量导入会话消息到情景记忆。
     * <p>
     * 将每条消息转换为 {@link EpisodicEvent} 实体并持久化，
     * 事件类型统一标记为 MESSAGE（不区分角色）。
     *
     * @param tenantId 租户 ID
     * @param sessionId 会话 ID，关联所有导入的消息
     * @param messages  消息列表，每条包含 userId、role、content
     * @return 成功导入的消息数量
     * @since 1.0.0
     */
    public int importSessionMessages(String tenantId, String sessionId, List<SessionMessageDTO> messages) {
        int count = 0;
        for (SessionMessageDTO msg : messages) {
            EpisodicEvent event = new EpisodicEvent();
            event.setId(UUID.randomUUID().toString());
            event.setTenantId(tenantId);
            event.setUserId(msg.getUserId());
            event.setSessionId(sessionId);
            // 当前实现不区分用户/助手消息，统一标记为 MESSAGE
            event.setEventType(msg.getRole().equals("assistant") ? "MESSAGE" : "MESSAGE");
            event.setContent(msg.getContent());
            event.setMetadata(null);
            event.setCreatedBy(msg.getUserId());
            event.setUpdatedBy(msg.getUserId());

            episodicEventMapper.insert(event);
            count++;
        }
        log.info("HistoryImportService.importSessionMessages: sessionId={}, count={}", sessionId, count);
        return count;
    }

    /**
     * 导入所有有效工具到记忆系统。
     * <p>
     * 查询状态为启用（status=1）的工具列表并记录日志。
     * 当前实现仅做遍历统计，实际导入逻辑需结合 {@link ProceduralMemoryService#registerTool} 扩展。
     *
     * @param tenantId 租户 ID
     * @return 导入的工具数量
     * @since 1.0.0
     */
    public int importAllTools(String tenantId) {
        List<AgentTool> tools = agentToolMapper.selectList(
                new LambdaQueryWrapper<AgentTool>().eq(AgentTool::getStatus, 1)
        );
        int count = 0;
        for (AgentTool tool : tools) {
            log.info("HistoryImportService.importAllTools: toolName={}", tool.getToolName());
            count++;
        }
        log.info("HistoryImportService.importAllTools: tenantId={}, count={}", tenantId, count);
        return count;
    }

    /**
     * 会话消息数据传输对象 — 用于批量导入时的消息结构。
     * <p>
     * 包含消息的基本属性：发送用户、角色、内容。
     *
     * @since 1.0.0
     */
    public static class SessionMessageDTO {
        /** 发送消息的用户 ID。 */
        private String userId;
        /** 消息角色（user / assistant）。 */
        private String role;
        /** 消息文本内容。 */
        private String content;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
}
