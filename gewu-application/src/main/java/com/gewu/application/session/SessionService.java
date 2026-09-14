package com.gewu.application.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gewu.application.session.dto.*;
import com.gewu.common.context.UserContext;
import com.gewu.common.dto.PageQuery;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.PageResult;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.session.Session;
import com.gewu.domain.session.SessionMember;
import com.gewu.domain.workspace.Workspace;
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.SessionMemberMapper;
import com.gewu.infrastructure.mapper.SessionMessageMapper;
import com.gewu.infrastructure.mapper.WorkspaceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 会话应用服务 — 会话的创建、查询、更新与删除.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionMapper sessionMapper;
    private final SessionMemberMapper sessionMemberMapper;
    private final SessionMessageMapper sessionMessageMapper;
    private final WorkspaceMapper workspaceMapper;

    @Transactional
    public SessionDTO createSession(CreateSessionCommand command) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Session session = new Session();
        session.setTitle(command.getTitle());
        session.setType(command.getType());
        session.setProjectId(command.getProjectId());
        session.setIsPublic(command.getIsPublic() != null ? command.getIsPublic() : 0);
        session.setAgent(command.getAgent());
        session.setDirectory(command.getDirectory());
        bindWorkspace(session, command.getProjectId(), command.getDirectory());
        session.setStatus(0);
        session.setMessageCount(0);
        sessionMapper.insert(session);

        SessionMember member = new SessionMember();
        member.setSessionId(session.getId());
        member.setUserId(userId);
        member.setRole(1);
        member.setJoinedAt(System.currentTimeMillis());
        sessionMemberMapper.insert(member);

        return toDTO(session);
    }

    /**
     * 工作空间绑定（S9 F1）：项目会话的文件操作走项目仓库目录
     * （/workspace/projects/{projectId}/repo），无项目会话绑定用户默认空间。
     * 工作空间解析失败不阻断会话创建（后续文件工具按需回退）。
     */
    private void bindWorkspace(Session session, String projectId, String directory) {
        try {
            Workspace ws = workspaceMapper.selectOne(
                    new LambdaQueryWrapper<Workspace>().eq(Workspace::getUserId, UserContext.currentUserId()));
            if (ws != null) {
                session.setWorkspaceId(ws.getId());
            }
        } catch (Exception ignored) {
            // 工作空间缺失/异常不阻断会话创建
        }
        if (projectId != null && !projectId.isBlank()
                && (directory == null || directory.isBlank())) {
            session.setDirectory("/workspace/projects/" + projectId + "/repo");
        }
    }

    @Transactional
    public SessionDTO createChatSession(CreateChatSessionCommand command) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Session session = new Session();
        session.setTitle(command.getTitle() != null ? command.getTitle() : "新对话");
        session.setType(1);
        session.setAgent(command.getAgentId());
        session.setStatus(0);
        session.setMessageCount(0);
        sessionMapper.insert(session);

        SessionMember member = new SessionMember();
        member.setSessionId(session.getId());
        member.setUserId(userId);
        member.setRole(1);
        member.setJoinedAt(System.currentTimeMillis());
        sessionMemberMapper.insert(member);

        return toDTO(session);
    }

    public SessionDTO getSession(String sessionId) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BusinessException.of(ResultCode.SESSION_NOT_FOUND);
        }
        // 权限校验：会话成员或公开会话可访问
        if (session.getIsPublic() != null && session.getIsPublic() == 1) {
            return toDTO(session);
        }
        checkMembership(sessionId, userId);
        return toDTO(session);
    }

    public PageResult<SessionDTO> listSessions(PageQuery query) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        // 获取用户参与的会话 ID
        List<SessionMember> members = sessionMemberMapper.selectList(
                new LambdaQueryWrapper<SessionMember>().eq(SessionMember::getUserId, userId));
        List<String> memberSessionIds = members.stream().map(SessionMember::getSessionId).toList();
        
        // 查询用户的会话 + 公开会话（置顶优先，其次最近活动）
        LambdaQueryWrapper<Session> wrapper = new LambdaQueryWrapper<Session>()
                .and(w -> {
                    w.in(Session::getId, memberSessionIds)
                     .or()
                     .eq(Session::getIsPublic, 1);
                })
                .orderByDesc(Session::getPinned)
                .orderByDesc(Session::getLastMessageAt)
                .orderByDesc(Session::getCreatedAt);
        
        Page<Session> page = new Page<>(query.getPage(), query.getSize());
        Page<Session> result = sessionMapper.selectPage(page, wrapper);
        List<SessionDTO> dtos = result.getRecords().stream().map(this::toDTO).toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    /**
     * 我的会话列表（S9 F1 过滤参数）。
     *
     * @param projectId    项目过滤：精确匹配；与 defaultSpace 二选一
     * @param defaultSpace true=仅无项目的默认空间会话（projectId IS NULL）
     * @param status       状态精确过滤（0 进行中/1 已完成/2 已归档）；缺省时默认排除已归档
     */
    public PageResult<SessionDTO> listMySessions(PageQuery query, String projectId,
                                                  boolean defaultSpace, Integer status) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        List<SessionMember> members = sessionMemberMapper.selectList(
                new LambdaQueryWrapper<SessionMember>().eq(SessionMember::getUserId, userId));
        if (members.isEmpty()) {
            return PageResult.empty(query.getPage(), query.getSize());
        }
        List<String> sessionIds = members.stream().map(SessionMember::getSessionId).toList();
        LambdaQueryWrapper<Session> wrapper = new LambdaQueryWrapper<Session>()
                .in(Session::getId, sessionIds);
        if (defaultSpace) {
            wrapper.isNull(Session::getProjectId);
        } else if (projectId != null && !projectId.isBlank()) {
            wrapper.eq(Session::getProjectId, projectId);
        }
        if (status != null) {
            wrapper.eq(Session::getStatus, status);
        } else {
            // 默认排除已归档会话（归档会话在「已归档」视图单独查看）
            wrapper.and(w -> w.isNull(Session::getStatus).or().ne(Session::getStatus, 2));
        }
        wrapper.orderByDesc(Session::getPinned)
                .orderByDesc(Session::getLastMessageAt)
                .orderByDesc(Session::getCreatedAt);
        Page<Session> page = new Page<>(query.getPage(), query.getSize());
        Page<Session> result = sessionMapper.selectPage(page, wrapper);
        List<SessionDTO> dtos = result.getRecords().stream().map(this::toDTO).toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    /** 兼容旧签名（无过滤） */
    public PageResult<SessionDTO> listMySessions(PageQuery query) {
        return listMySessions(query, null, false, null);
    }

    @Transactional
    public SessionDTO updateSession(String sessionId, UpdateSessionCommand command) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BusinessException.of(ResultCode.SESSION_NOT_FOUND);
        }
        checkMembership(sessionId, userId);

        if (command.getTitle() != null) session.setTitle(command.getTitle());
        if (command.getStatus() != null) session.setStatus(command.getStatus());
        if (command.getIsPublic() != null) session.setIsPublic(command.getIsPublic());
        if (command.getAgent() != null) session.setAgent(command.getAgent());
        if (command.getDirectory() != null) session.setDirectory(command.getDirectory());
        sessionMapper.updateById(session);

        return toDTO(session);
    }

    @Transactional
    public void deleteSession(String sessionId) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BusinessException.of(ResultCode.SESSION_NOT_FOUND);
        }
        checkMembership(sessionId, userId);
        sessionMapper.deleteById(sessionId);
    }

    // ==================== 会话增值生命周期（T3.3） ====================

    /** 归档：status=2 并记录归档时间（需成员身份） */
    @Transactional
    public SessionDTO archiveSession(String sessionId) {
        requireMemberSession(sessionId);
        Session session = sessionMapper.selectById(sessionId);
        session.setStatus(2);
        session.setTimeArchived(System.currentTimeMillis());
        sessionMapper.updateById(session);
        return toDTO(session);
    }

    /** 取消归档：status=0 并清空归档时间 */
    @Transactional
    public SessionDTO unarchiveSession(String sessionId) {
        requireMemberSession(sessionId);
        Session session = sessionMapper.selectById(sessionId);
        if (session.getStatus() == null || session.getStatus() != 2) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "仅已归档会话可取消归档");
        }
        session.setStatus(0);
        session.setTimeArchived(null);
        sessionMapper.updateById(session);
        return toDTO(session);
    }

    /**
     * 分享：生成短 slug 并置公开。幂等--已有 slug 直接返回既有分享信息。
     *
     * @return 分享信息（slug + shareUrl）
     */
    @Transactional
    public SessionDTO shareSession(String sessionId) {
        requireMemberSession(sessionId);
        Session session = sessionMapper.selectById(sessionId);
        if (session.getSlug() == null || session.getSlug().isBlank()) {
            session.setSlug(generateSlug());
            session.setShareUrl("/share/" + session.getSlug());
            sessionMapper.updateById(session);
        }
        session.setIsPublic(1);
        sessionMapper.updateById(session);
        return toDTO(session);
    }

    /** 取消分享：关闭公开并清空 slug/shareUrl */
    @Transactional
    public SessionDTO unshareSession(String sessionId) {
        requireMemberSession(sessionId);
        Session session = sessionMapper.selectById(sessionId);
        session.setIsPublic(0);
        session.setSlug(null);
        session.setShareUrl(null);
        sessionMapper.updateById(session);
        return toDTO(session);
    }

    /** 按 slug 公开读取分享的会话（免鉴权路径使用，只返回脱敏元数据） */
    public SessionDTO getSharedSession(String slug) {
        if (slug == null || slug.isBlank()) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "分享链接无效");
        }
        Session session = sessionMapper.selectOne(
                new LambdaQueryWrapper<Session>()
                        .eq(Session::getSlug, slug)
                        .eq(Session::getIsPublic, 1)
                        .last("LIMIT 1"));
        if (session == null) {
            throw BusinessException.of(ResultCode.SESSION_NOT_FOUND, "分享不存在或已取消");
        }
        SessionDTO dto = toDTO(session);
        // 分享视图脱敏：不暴露创建者 ID
        dto.setCreatedBy(null);
        return dto;
    }

    /** 置顶 / 取消置顶 */
    @Transactional
    public SessionDTO pinSession(String sessionId, boolean pinned) {
        requireMemberSession(sessionId);
        Session session = sessionMapper.selectById(sessionId);
        session.setPinned(pinned ? 1 : 0);
        sessionMapper.updateById(session);
        return toDTO(session);
    }

    private Session requireMemberSession(String sessionId) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BusinessException.of(ResultCode.SESSION_NOT_FOUND);
        }
        checkMembership(sessionId, userId);
        return session;
    }

    /** 生成 10 位小写分享 slug（ULID 前 10 位，含时间序保证唯一性概率） */
    private String generateSlug() {
        String slug = com.gewu.common.ulid.Ulid.next().toLowerCase();
        return slug.length() > 10 ? slug.substring(0, 10) : slug;
    }

    public List<SessionMemberDTO> getSessionMembers(String sessionId) {
        String userId = UserContext.currentUserId();
        if (userId == null) {
            throw BusinessException.of(ResultCode.UNAUTHORIZED);
        }
        // 权限校验：会话成员或公开会话可访问
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BusinessException.of(ResultCode.SESSION_NOT_FOUND);
        }
        if (session.getIsPublic() == null || session.getIsPublic() != 1) {
            checkMembership(sessionId, userId);
        }
        
        List<SessionMember> members = sessionMemberMapper.selectList(
                new LambdaQueryWrapper<SessionMember>().eq(SessionMember::getSessionId, sessionId));
        return members.stream()
                .map(m -> SessionMemberDTO.builder()
                        .userId(m.getUserId())
                        .role(m.getRole())
                        .joinedAt(m.getJoinedAt())
                        .build())
                .toList();
    }

    private void checkMembership(String sessionId, String userId) {
        Long count = sessionMemberMapper.selectCount(
                new LambdaQueryWrapper<SessionMember>()
                        .eq(SessionMember::getSessionId, sessionId)
                        .eq(SessionMember::getUserId, userId));
        if (count == 0) {
            throw BusinessException.of(ResultCode.FORBIDDEN);
        }
    }

    private SessionDTO toDTO(Session session) {
        return SessionDTO.builder()
                .sessionId(session.getId())
                .title(session.getTitle())
                .type(session.getType())
                .typeDesc(typeDesc(session.getType()))
                .projectId(session.getProjectId())
                .status(session.getStatus())
                .statusDesc(statusDesc(session.getStatus()))
                .isPublic(session.getIsPublic())
                .pinned(session.getPinned())
                .messageCount(session.getMessageCount())
                .lastMessageAt(session.getLastMessageAt())
                .agent(session.getAgent())
                .directory(session.getDirectory())
                .workspaceId(session.getWorkspaceId())
                .slug(session.getSlug())
                .shareUrl(session.getShareUrl())
                .createdAt(session.getCreatedAt())
                .createdBy(session.getCreatedBy())
                .build();
    }

    private String typeDesc(Integer type) {
        if (type == null) return null;
        return switch (type) {
            case 1 -> "对话";
            case 2 -> "编码";
            case 3 -> "调试";
            case 4 -> "重构";
            default -> "未知";
        };
    }

    private String statusDesc(Integer status) {
        if (status == null) return null;
        return switch (status) {
            case 0 -> "进行中";
            case 1 -> "已完成";
            case 2 -> "已归档";
            default -> "未知";
        };
    }
}
