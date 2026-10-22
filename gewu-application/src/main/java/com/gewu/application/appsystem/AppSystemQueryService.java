package com.gewu.application.appsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.domain.appsystem.AppSystem;
import com.gewu.infrastructure.mapper.AppSystemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 应用系统选项查询服务（设计文档 58 关联：新建项目"所属系统"选择器数据源）.
 *
 * <p>管理端（app-systems 台账）配置的启用系统，供业务侧只读选择；
 * 完整 CRUD 仍在管理端（appsystem:manage 权限门内）。
 */
@Service
@RequiredArgsConstructor
public class AppSystemQueryService {

    private final AppSystemMapper appSystemMapper;

    /** 选项视图。 */
    public record AppSystemOption(String systemId, String systemCode, String systemName,
                                  String shortName, String systemType, String ownerName) {}

    /**
     * 启用中的系统选项列表：keyword 模糊匹配系统名称/编码/简称（可空）。
     */
    public List<AppSystemOption> listEnabled(String keyword) {
        LambdaQueryWrapper<AppSystem> wrapper = new LambdaQueryWrapper<AppSystem>()
                .eq(AppSystem::getStatus, 1);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(AppSystem::getSystemName, kw)
                    .or().like(AppSystem::getSystemCode, kw)
                    .or().like(AppSystem::getShortName, kw));
        }
        wrapper.orderByAsc(AppSystem::getSortOrder).orderByAsc(AppSystem::getSystemName);
        return appSystemMapper.selectList(wrapper).stream()
                .map(s -> new AppSystemOption(s.getId(), s.getSystemCode(), s.getSystemName(),
                        s.getShortName(), s.getSystemType(), s.getOwnerName()))
                .toList();
    }
}
