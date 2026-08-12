package com.gewu.application.org;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.org.dto.CreateOrgCommand;
import com.gewu.application.org.dto.OrgDTO;
import com.gewu.application.org.dto.UpdateOrgCommand;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.domain.org.Organization;
import com.gewu.domain.user.UserAccount;
import com.gewu.infrastructure.mapper.OrganizationMapper;
import com.gewu.infrastructure.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 组织机构管理服务 - 机构树构建与 CRUD.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgService {

    private final OrganizationMapper orgMapper;
    private final UserAccountMapper userAccountMapper;

    /** 全量机构树（含 userCount） */
    public List<OrgDTO> listOrgTree() {
        List<Organization> orgs = orgMapper.selectList(
                new LambdaQueryWrapper<Organization>().orderByAsc(Organization::getSortOrder));
        return buildTree(orgs);
    }

    @Transactional
    public OrgDTO createOrg(CreateOrgCommand command) {
        if (command.getParentId() != null && !command.getParentId().isBlank()) {
            Organization parent = orgMapper.selectById(command.getParentId());
            if (parent == null) {
                throw BusinessException.of(ResultCode.NOT_FOUND, "父机构不存在");
            }
        }
        // 校验编码唯一
        Long existing = orgMapper.selectCount(
                new LambdaQueryWrapper<Organization>().eq(Organization::getOrgCode, command.getOrgCode()));
        if (existing > 0) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "机构编码已存在");
        }
        Organization org = new Organization();
        org.setParentId(command.getParentId());
        org.setOrgName(command.getOrgName());
        org.setOrgCode(command.getOrgCode());
        org.setSortOrder(command.getSortOrder() != null ? command.getSortOrder() : 99);
        orgMapper.insert(org);
        log.info("创建机构: id={}, code={}", org.getId(), org.getOrgCode());
        return toDTO(org);
    }

    @Transactional
    public OrgDTO updateOrg(String orgId, UpdateOrgCommand command) {
        Organization org = orgMapper.selectById(orgId);
        if (org == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "机构不存在");
        }
        if (command.getParentId() != null) {
            String newParentId = command.getParentId().isBlank() ? null : command.getParentId();
            // 不能将自己设为父机构
            if (orgId.equals(newParentId)) {
                throw BusinessException.of(ResultCode.PARAM_INVALID, "不能将自身设为父机构");
            }
            org.setParentId(newParentId);
        }
        if (command.getOrgName() != null) org.setOrgName(command.getOrgName());
        if (command.getOrgCode() != null) org.setOrgCode(command.getOrgCode());
        if (command.getSortOrder() != null) org.setSortOrder(command.getSortOrder());
        orgMapper.updateById(org);
        return toDTO(org);
    }

    @Transactional
    public void deleteOrg(String orgId) {
        Organization org = orgMapper.selectById(orgId);
        if (org == null) {
            throw BusinessException.of(ResultCode.NOT_FOUND, "机构不存在");
        }
        // 校验子机构
        Long childCount = orgMapper.selectCount(
                new LambdaQueryWrapper<Organization>().eq(Organization::getParentId, orgId));
        if (childCount > 0) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "存在子机构，无法删除");
        }
        // 校验用户归属
        Long userCount = userAccountMapper.selectCount(
                new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getOrgId, orgId));
        if (userCount > 0) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "机构下存在用户，无法删除");
        }
        orgMapper.deleteById(orgId);
        log.info("删除机构: id={}", orgId);
    }

    // ==================== 私有方法 ====================

    private List<OrgDTO> buildTree(List<Organization> orgs) {
        Map<String, List<Organization>> byParent = orgs.stream()
                .collect(Collectors.groupingBy(o -> o.getParentId() != null ? o.getParentId() : "ROOT"));
        return buildChildren(byParent, "ROOT");
    }

    private List<OrgDTO> buildChildren(Map<String, List<Organization>> byParent, String parentKey) {
        List<Organization> children = byParent.get(parentKey);
        if (children == null || children.isEmpty()) return Collections.emptyList();
        return children.stream()
                .map(o -> {
                    OrgDTO dto = toDTO(o);
                    dto.setChildren(buildChildren(byParent, o.getId()));
                    return dto;
                })
                .toList();
    }

    private OrgDTO toDTO(Organization org) {
        Long userCount = userAccountMapper.selectCount(
                new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getOrgId, org.getId()));
        return OrgDTO.builder()
                .orgId(org.getId())
                .parentId(org.getParentId())
                .orgName(org.getOrgName())
                .orgCode(org.getOrgCode())
                .sortOrder(org.getSortOrder())
                .userCount(userCount != null ? userCount.intValue() : 0)
                .children(Collections.emptyList())
                .build();
    }
}
