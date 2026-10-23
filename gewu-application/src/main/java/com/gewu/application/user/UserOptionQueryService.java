package com.gewu.application.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.domain.user.UserAccount;
import com.gewu.infrastructure.mapper.UserAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户选项查询服务（新建项目"团队与角色"选择器数据源）.
 *
 * <p>用户侧只读查询启用中的用户；完整用户管理仍在管理端（admin-server），
 * 此处仅开放选择器所需的只读列表。
 */
@Service
@RequiredArgsConstructor
public class UserOptionQueryService {

    private final UserAccountMapper userAccountMapper;

    /** 选项视图（不含凭证等敏感字段）。 */
    public record UserOption(String userId, String username, String displayName, String email) {}

    /**
     * 启用中的用户选项列表：keyword 模糊匹配显示名/用户名/邮箱（可空）。
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
