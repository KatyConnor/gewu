package com.gewu.application.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.project.dto.ProjectPhaseDTO;
import com.gewu.common.context.UserContext;
import com.gewu.common.enums.PhaseCode;
import com.gewu.common.enums.PhaseStatus;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.project.Project;
import com.gewu.domain.project.ProjectPhase;
import com.gewu.infrastructure.mapper.PhaseDocumentMapper;
import com.gewu.infrastructure.mapper.ProjectMapper;
import com.gewu.infrastructure.mapper.ProjectPhaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectPhaseService {

    private final ProjectPhaseMapper phaseMapper;
    private final ProjectMapper projectMapper;
    private final PhaseDocumentMapper documentMapper;

    public List<ProjectPhaseDTO> getPhases(String projectId) {
        List<ProjectPhase> phases = phaseMapper.selectList(
                new LambdaQueryWrapper<ProjectPhase>()
                        .eq(ProjectPhase::getProjectId, projectId)
                        .orderByAsc(ProjectPhase::getPhaseOrder));
        return phases.stream().map(this::toDTO).toList();
    }

    @Transactional
    public void startPhase(String projectId, String phaseCode) {
        ProjectPhase current = getPhaseOrThrow(projectId, phaseCode);
        if (current.getStatus() == PhaseStatus.IN_PROGRESS.getCode()) {
            return;
        }
        validateSequential(projectId, phaseCode, PhaseStatus.NOT_STARTED);
        current.setStatus(PhaseStatus.IN_PROGRESS.getCode());
        current.setStartedAt(System.currentTimeMillis());
        phaseMapper.updateById(current);

        Project project = projectMapper.selectById(projectId);
        if (project != null) {
            project.setCurrentPhase(phaseCode);
            project.setInitiatedAt(project.getInitiatedAt() == null ? System.currentTimeMillis() : project.getInitiatedAt());
            projectMapper.updateById(project);
        }
        log.info("阶段已开始: projectId={}, phase={}", projectId, phaseCode);
    }

    @Transactional
    public void completePhase(String projectId, String phaseCode) {
        ProjectPhase current = getPhaseOrThrow(projectId, phaseCode);
        if (current.getStatus() == PhaseStatus.COMPLETED.getCode()) {
            return;
        }
        validateSequential(projectId, phaseCode, PhaseStatus.IN_PROGRESS);

        long docCount = documentMapper.selectCount(
                new LambdaQueryWrapper<com.gewu.domain.project.PhaseDocument>()
                        .eq(com.gewu.domain.project.PhaseDocument::getProjectId, projectId)
                        .eq(com.gewu.domain.project.PhaseDocument::getPhaseCode, phaseCode));
        if (docCount == 0) {
            throw BusinessException.of(ResultCode.PHASE_DOCUMENT_REQUIRED);
        }

        current.setStatus(PhaseStatus.COMPLETED.getCode());
        current.setCompletedAt(System.currentTimeMillis());
        phaseMapper.updateById(current);

        PhaseCode nextPhase = PhaseCode.next(PhaseCode.valueOf(phaseCode));
        if (nextPhase != null) {
            ProjectPhase next = getPhaseOrThrow(projectId, nextPhase.name());
            next.setStatus(PhaseStatus.NOT_STARTED.getCode());
            phaseMapper.updateById(next);
        }
        log.info("阶段已完成: projectId={}, phase={}", projectId, phaseCode);
    }

    @Transactional
    public void revertPhase(String projectId, String phaseCode) {
        PhaseCode phase = PhaseCode.valueOf(phaseCode);
        if (!phase.isRevertible()) {
            throw BusinessException.of(ResultCode.PHASE_REVERT_NOT_ALLOWED);
        }
        ProjectPhase current = getPhaseOrThrow(projectId, phaseCode);
        current.setStatus(PhaseStatus.IN_PROGRESS.getCode());
        current.setCompletedAt(null);
        phaseMapper.updateById(current);

        PhaseCode nextPhase = PhaseCode.next(phase);
        if (nextPhase != null) {
            ProjectPhase next = getPhaseOrThrow(projectId, nextPhase.name());
            next.setStatus(PhaseStatus.NOT_STARTED.getCode());
            next.setStartedAt(null);
            phaseMapper.updateById(next);
        }
        log.info("阶段已回退: projectId={}, phase={}", projectId, phaseCode);
    }

    @Transactional
    public void initializePhases(String projectId) {
        for (PhaseCode pc : PhaseCode.values()) {
            ProjectPhase phase = new ProjectPhase();
            phase.setProjectId(projectId);
            phase.setPhaseCode(pc.name());
            phase.setPhaseOrder(pc.getOrder());
            phase.setStatus(PhaseStatus.NOT_STARTED.getCode());
            phaseMapper.insert(phase);
        }
        log.info("项目阶段已初始化: projectId={}, 阶段数={}", projectId, PhaseCode.values().length);
    }

    private void validateSequential(String projectId, String phaseCode, PhaseStatus requiredCurrentStatus) {
        List<ProjectPhase> phases = phaseMapper.selectList(
                new LambdaQueryWrapper<ProjectPhase>()
                        .eq(ProjectPhase::getProjectId, projectId)
                        .orderByAsc(ProjectPhase::getPhaseOrder));
        PhaseCode target = PhaseCode.valueOf(phaseCode);

        PhaseCode previous = PhaseCode.prev(target);
        if (previous != null) {
            ProjectPhase prevPhase = phases.stream()
                    .filter(p -> p.getPhaseCode().equals(previous.name()))
                    .findFirst().orElse(null);
            if (prevPhase == null || prevPhase.getStatus() != PhaseStatus.COMPLETED.getCode()) {
                throw BusinessException.of(ResultCode.PHASE_ORDER_INVALID,
                        "请先完成上一阶段: " + previous.getDisplayName());
            }
        }
    }

    private ProjectPhase getPhaseOrThrow(String projectId, String phaseCode) {
        ProjectPhase phase = phaseMapper.selectOne(
                new LambdaQueryWrapper<ProjectPhase>()
                        .eq(ProjectPhase::getProjectId, projectId)
                        .eq(ProjectPhase::getPhaseCode, phaseCode));
        if (phase == null) {
            throw BusinessException.of(ResultCode.PHASE_NOT_FOUND);
        }
        return phase;
    }

    private ProjectPhaseDTO toDTO(ProjectPhase phase) {
        PhaseCode pc = PhaseCode.valueOf(phase.getPhaseCode());
        PhaseStatus status = PhaseStatus.fromCode(phase.getStatus() != null ? phase.getStatus() : 0);
        long docCount = documentMapper.selectCount(
                new LambdaQueryWrapper<com.gewu.domain.project.PhaseDocument>()
                        .eq(com.gewu.domain.project.PhaseDocument::getProjectId, phase.getProjectId())
                        .eq(com.gewu.domain.project.PhaseDocument::getPhaseCode, phase.getPhaseCode()));
        return ProjectPhaseDTO.builder()
                .id(phase.getId())
                .projectId(phase.getProjectId())
                .phaseCode(phase.getPhaseCode())
                .phaseName(pc.getDisplayName())
                .phaseOrder(phase.getPhaseOrder())
                .status(phase.getStatus())
                .statusDesc(status.getDescription())
                .startedAt(phase.getStartedAt())
                .completedAt(phase.getCompletedAt())
                .agentId(phase.getAgentId())
                .revertible(pc.isRevertible())
                .documentCount((int) docCount)
                .build();
    }
}
