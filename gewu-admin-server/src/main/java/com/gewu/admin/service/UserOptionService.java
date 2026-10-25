package com.gewu.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.domain.user.UserAccount;
import com.gewu.infrastructure.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理端用户选项查询服务（项目管理"团队与角色"选择器数据源）.
 */
@Service
@RequiredArgsConstructor
public class UserOptionService {

    private final UserAccountMapper userAccountMapper;

    /** 选项视图（不含凭证等敏感字段） */
    public record UserOption(String userId, String username, String displayName, String email) {}

    /**
     * 启用中的用户选项列表：keyword 模糊匹配显示名/用户名/邮箱（可空），限 50 条。
     */
    public List<UserOption> listEnabled(String keyword) {
        LambdaQueryWrapper<UserAccount> wrapper = new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getStatus, 1)
                .orderByAsc(UserAccount::getDisplayName);
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(UserAccount::getDisplayName, kw)
                    .or().like(UserAccount::getUsername, kw)
                    .or().like(UserAccount::getEmail, kw));
        }
        wrapper.last("LIMIT 50");
        return userAccountMapper.selectList(wrapper).stream()
                .map(u -> new UserOption(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail()))
                .toList();
    }
}
