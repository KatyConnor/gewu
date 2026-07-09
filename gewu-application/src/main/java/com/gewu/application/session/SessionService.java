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
import com.gewu.infrastructure.mapper.SessionMapper;
import com.gewu.infrastructure.mapper.SessionMemberMapper;
import com.gewu.infrastructure.mapper.SessionMessageMapper;
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
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BusinessException.of(ResultCode.SESSION_NOT_FOUND);
        }
        return toDTO(session);
    }

    public PageResult<SessionDTO> listSessions(PageQuery query) {
        Page<Session> page = new Page<>(query.getPage(), query.getSize());
        Page<Session> result = sessionMapper.selectPage(page,
                new LambdaQueryWrapper<Session>().orderByDesc(Session::getCreatedAt));
        List<SessionDTO> dtos = result.getRecords().stream().map(this::toDTO).toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
    }

    public PageResult<SessionDTO> listMySessions(PageQuery query) {
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
        Page<Session> page = new Page<>(query.getPage(), query.getSize());
        Page<Session> result = sessionMapper.selectPage(page,
                new LambdaQueryWrapper<Session>()
                        .in(Session::getId, sessionIds)
                        .orderByDesc(Session::getCreatedAt));
        List<SessionDTO> dtos = result.getRecords().stream().map(this::toDTO).toList();
        return PageResult.of(dtos, result.getTotal(), query.getPage(), query.getSize());
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

    public List<SessionMemberDTO> getSessionMembers(String sessionId) {
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
                .messageCount(session.getMessageCount())
                .lastMessageAt(session.getLastMessageAt())
                .agent(session.getAgent())
                .directory(session.getDirectory())
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
