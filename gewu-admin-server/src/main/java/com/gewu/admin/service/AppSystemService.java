package com.gewu.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.admin.dto.appsystem.*;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.appsystem.AppService;
import com.gewu.domain.appsystem.AppSystem;
import com.gewu.domain.org.Organization;
import com.gewu.infrastructure.mapper.AppServiceMapper;
import com.gewu.infrastructure.mapper.AppSystemMapper;
import com.gewu.infrastructure.mapper.OrganizationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 应用系统管理服务 - 应用系统与子模块服务的 CRUD 与归属校验.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppSystemService {

    /** 系统分类合法值 */
    private static final Set<String> SYSTEM_TYPES = Set.of("BUSINESS", "PLATFORM", "MIDDLEWARE", "DATA", "TOOL");
    /** 服务类型合法值 */
    private static final Set<String> SERVICE_TYPES =
            Set.of("BACKEND", "FRONTEND", "GATEWAY", "DATABASE", "MIDDLEWARE", "SCHEDULE", "MQ");

    private final AppSystemMapper systemMapper;
    private final AppServiceMapper serviceMapper;
    private final OrganizationMapper orgMapper;

    // ==================== 应用系统 ====================

    /** 系统列表（keyword 模糊匹配编码/名称/简称，含服务数与机构名） */
    public List<AppSystemDTO> listSystems(String keyword) {
        LambdaQueryWrapper<AppSystem> wrapper = new LambdaQueryWrapper<AppSystem>()
                .orderByAsc(AppSystem::getSortOrder)
                .orderByDesc(AppSystem::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(AppSystem::getSystemName, kw)
                    .or().like(AppSystem::getSystemCode, kw)
                    .or().like(AppSystem::getShortName, kw));
        }
        List<AppSystem> systems = systemMapper.selectList(wrapper);
        if (systems.isEmpty()) return Collections.emptyList();

        // 批量取服务数与机构名，避免 N+1
        List<String> systemIds = systems.stream().map(AppSystem::getId).toList();
        Map<String, Long> serviceCounts = serviceMapper.selectList(
                        new LambdaQueryWrapper<AppService>().in(AppService::getSystemId, systemIds)).stream()
                .collect(Collectors.groupingBy(AppService::getSystemId, Collectors.counting()));
        Set<String> orgIds = systems.stream().map(AppSystem::getOrgId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> orgNames = orgIds.isEmpty() ? Collections.emptyMap()
                : orgMapper.selectBatchIds(orgIds).stream()
                        .collect(Collectors.toMap(Organization::getId, Organization::getOrgName));

        return systems.stream().map(s -> toDTO(s,
                serviceCounts.getOrDefault(s.getId(), 0L).intValue(),
                s.getOrgId() != null ? orgNames.get(s.getOrgId()) : null))
                .toList();
    }

    @Transactional
    public AppSystemDTO createSystem(CreateAppSystemCommand command) {
        if (!SYSTEM_TYPES.contains(command.getSystemType())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "系统分类非法");
        }
        if (command.getCriticality() == null || command.getCriticality() < 1 || command.getCriticality() > 3) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "重要等级非法（1核心 2重要 3一般）");
        }
        Long existing = systemMapper.selectCount(
                new LambdaQueryWrapper<AppSystem>().eq(AppSystem::getSystemCode, command.getSystemCode()));
        if (existing > 0) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "系统编码已存在");
        }
        validateOrgRef(command.getOrgId());
        AppSystem system = new AppSystem();
        applyCreate(system, command);
        systemMapper.insert(system);
        log.info("创建应用系统: id={}, code={}", system.getId(), system.getSystemCode());
        return toDTO(system, 0, resolveOrgName(system.getOrgId()));
    }

    @Transactional
    public AppSystemDTO updateSystem(String systemId, UpdateAppSystemCommand command) {
        AppSystem system = systemMapper.selectById(systemId);
        if (system == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "应用系统不存在");
        }
        if (command.getSystemType() != null && !SYSTEM_TYPES.contains(command.getSystemType())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "系统分类非法");
        }
        if (command.getCriticality() != null && (command.getCriticality() < 1 || command.getCriticality() > 3)) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "重要等级非法（1核心 2重要 3一般）");
        }
        if (command.getOrgId() != null) {
            validateOrgRef(command.getOrgId().isBlank() ? null : command.getOrgId());
            system.setOrgId(command.getOrgId().isBlank() ? null : command.getOrgId());
        }
        if (command.getSystemName() != null) system.setSystemName(command.getSystemName());
        if (command.getShortName() != null) system.setShortName(command.getShortName());
        if (command.getSystemType() != null) system.setSystemType(command.getSystemType());
        if (command.getOwnerName() != null) system.setOwnerName(command.getOwnerName());
        if (command.getOwnerContact() != null) system.setOwnerContact(command.getOwnerContact());
        if (command.getRepoUrl() != null) system.setRepoUrl(command.getRepoUrl());
        if (command.getCriticality() != null) system.setCriticality(command.getCriticality());
        if (command.getStatus() != null) {
            if (command.getStatus() != 1 && command.getStatus() != 2) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "运行状态非法（1启用 2停用）");
            }
            system.setStatus(command.getStatus());
        }
        if (command.getDescription() != null) system.setDescription(command.getDescription());
        if (command.getSortOrder() != null) system.setSortOrder(command.getSortOrder());
        systemMapper.updateById(system);
        return toDTO(system, countServices(systemId).intValue(), resolveOrgName(system.getOrgId()));
    }

    @Transactional
    public void deleteSystem(String systemId) {
        AppSystem system = systemMapper.selectById(systemId);
        if (system == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "应用系统不存在");
        }
        Long serviceCount = countServices(systemId);
        if (serviceCount > 0) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "系统下存在 " + serviceCount + " 个子模块服务，请先删除服务");
        }
        systemMapper.deleteById(systemId);
        log.info("删除应用系统: id={}, code={}", systemId, system.getSystemCode());
    }

    // ==================== 子模块服务 ====================

    /** 系统下的服务列表（按排序号） */
    public List<AppServiceDTO> listServices(String systemId) {
        requireSystem(systemId);
        return serviceMapper.selectList(new LambdaQueryWrapper<AppService>()
                        .eq(AppService::getSystemId, systemId)
                        .orderByAsc(AppService::getSortOrder)
                        .orderByAsc(AppService::getCreatedAt))
                .stream().map(this::toDTO).toList();
    }

    @Transactional
    public AppServiceDTO createService(String systemId, CreateAppServiceCommand command) {
        requireSystem(systemId);
        if (!SERVICE_TYPES.contains(command.getServiceType())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "服务类型非法");
        }
        Long existing = serviceMapper.selectCount(new LambdaQueryWrapper<AppService>()
                .eq(AppService::getSystemId, systemId)
                .eq(AppService::getServiceCode, command.getServiceCode()));
        if (existing > 0) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "该系统下服务编码已存在");
        }
        AppService service = new AppService();
        service.setSystemId(systemId);
        service.setServiceCode(command.getServiceCode());
        service.setServiceName(command.getServiceName());
        service.setServiceType(command.getServiceType());
        service.setBaseUrl(trimToNull(command.getBaseUrl()));
        service.setHealthPath(trimToNull(command.getHealthPath()));
        service.setVersion(trimToNull(command.getVersion()));
        service.setOwnerName(command.getOwnerName());
        service.setStatus(command.getStatus() != null ? command.getStatus() : 1);
        service.setDescription(trimToNull(command.getDescription()));
        service.setSortOrder(command.getSortOrder() != null ? command.getSortOrder() : 0);
        serviceMapper.insert(service);
        log.info("创建子模块服务: id={}, systemId={}, code={}", service.getId(), systemId, service.getServiceCode());
        return toDTO(service);
    }

    @Transactional
    public AppServiceDTO updateService(String systemId, String serviceId, UpdateAppServiceCommand command) {
        requireSystem(systemId);
        AppService service = serviceMapper.selectById(serviceId);
        if (service == null || !systemId.equals(service.getSystemId())) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "子模块服务不存在");
        }
        if (command.getServiceType() != null && !SERVICE_TYPES.contains(command.getServiceType())) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "服务类型非法");
        }
        if (command.getServiceName() != null) service.setServiceName(command.getServiceName());
        if (command.getServiceType() != null) service.setServiceType(command.getServiceType());
        if (command.getBaseUrl() != null) service.setBaseUrl(trimToNull(command.getBaseUrl()));
        if (command.getHealthPath() != null) service.setHealthPath(trimToNull(command.getHealthPath()));
        if (command.getVersion() != null) service.setVersion(trimToNull(command.getVersion()));
        if (command.getOwnerName() != null) service.setOwnerName(command.getOwnerName());
        if (command.getStatus() != null) {
            if (command.getStatus() != 1 && command.getStatus() != 2) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "运行状态非法（1启用 2停用）");
            }
            service.setStatus(command.getStatus());
        }
        if (command.getDescription() != null) service.setDescription(trimToNull(command.getDescription()));
        if (command.getSortOrder() != null) service.setSortOrder(command.getSortOrder());
        serviceMapper.updateById(service);
        return toDTO(service);
    }

    @Transactional
    public void deleteService(String systemId, String serviceId) {
        requireSystem(systemId);
        AppService service = serviceMapper.selectById(serviceId);
        if (service == null || !systemId.equals(service.getSystemId())) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "子模块服务不存在");
        }
        serviceMapper.deleteById(serviceId);
        log.info("删除子模块服务: id={}, systemId={}", serviceId, systemId);
    }

    // ==================== 私有方法 ====================

    private void requireSystem(String systemId) {
        if (systemMapper.selectById(systemId) == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "应用系统不存在");
        }
    }

    private void validateOrgRef(String orgId) {
        if (orgId == null || orgId.isBlank()) return;
        if (orgMapper.selectById(orgId) == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "所属机构不存在");
        }
    }

    private void applyCreate(AppSystem system, CreateAppSystemCommand command) {
        system.setSystemCode(command.getSystemCode());
        system.setSystemName(command.getSystemName());
        system.setShortName(trimToNull(command.getShortName()));
        system.setSystemType(command.getSystemType());
        system.setOrgId(trimToNull(command.getOrgId()));
        system.setOwnerName(command.getOwnerName());
        system.setOwnerContact(trimToNull(command.getOwnerContact()));
        system.setRepoUrl(trimToNull(command.getRepoUrl()));
        system.setCriticality(command.getCriticality());
        system.setStatus(command.getStatus() != null ? command.getStatus() : 1);
        system.setDescription(trimToNull(command.getDescription()));
        system.setSortOrder(command.getSortOrder() != null ? command.getSortOrder() : 0);
    }

    private Long countServices(String systemId) {
        return serviceMapper.selectCount(
                new LambdaQueryWrapper<AppService>().eq(AppService::getSystemId, systemId));
    }

    private String resolveOrgName(String orgId) {
        if (orgId == null) return null;
        Organization org = orgMapper.selectById(orgId);
        return org != null ? org.getOrgName() : null;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AppSystemDTO toDTO(AppSystem system, int serviceCount, String orgName) {
        return AppSystemDTO.builder()
                .systemId(system.getId())
                .systemCode(system.getSystemCode())
                .systemName(system.getSystemName())
                .shortName(system.getShortName())
                .systemType(system.getSystemType())
                .orgId(system.getOrgId())
                .orgName(orgName)
                .ownerName(system.getOwnerName())
                .ownerContact(system.getOwnerContact())
                .repoUrl(system.getRepoUrl())
                .criticality(system.getCriticality())
                .status(system.getStatus())
                .description(system.getDescription())
                .sortOrder(system.getSortOrder())
                .serviceCount(serviceCount)
                .createdAt(system.getCreatedAt())
                .updatedAt(system.getUpdatedAt())
                .build();
    }

    private AppServiceDTO toDTO(AppService service) {
        return AppServiceDTO.builder()
                .serviceId(service.getId())
                .systemId(service.getSystemId())
                .serviceCode(service.getServiceCode())
                .serviceName(service.getServiceName())
                .serviceType(service.getServiceType())
                .baseUrl(service.getBaseUrl())
                .healthPath(service.getHealthPath())
                .version(service.getVersion())
                .ownerName(service.getOwnerName())
                .status(service.getStatus())
                .description(service.getDescription())
                .sortOrder(service.getSortOrder())
                .createdAt(service.getCreatedAt())
                .updatedAt(service.getUpdatedAt())
                .build();
    }
}
