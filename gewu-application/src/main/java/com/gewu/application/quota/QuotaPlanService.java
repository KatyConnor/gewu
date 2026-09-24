package com.gewu.application.quota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.application.quota.dto.QuotaPlanDTO;
import com.gewu.application.quota.dto.QuotaWindowItemDTO;
import com.gewu.application.quota.dto.SaveQuotaPlanCommand;
import com.gewu.common.context.UserContext;
import com.gewu.domain.quota.QuotaWindowType;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import com.gewu.common.ulid.Ulid;
import com.gewu.domain.quota.QuotaPlan;
import com.gewu.domain.quota.QuotaPlanItem;
import com.gewu.domain.quota.UserQuotaBinding;
import com.gewu.infrastructure.mapper.QuotaPlanItemMapper;
import com.gewu.infrastructure.mapper.QuotaPlanMapper;
import com.gewu.infrastructure.mapper.UserQuotaBindingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 配额套餐服务（管理员）：套餐 CRUD（含窗口项）与用户绑定。
 * <p>绑定采用"一用户一生效绑定"语义：绑定 = 失效旧绑定 + 写新绑定；解绑 = 逻辑删除。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaPlanService {

    private final QuotaPlanMapper quotaPlanMapper;
    private final QuotaPlanItemMapper quotaPlanItemMapper;
    private final UserQuotaBindingMapper userQuotaBindingMapper;

    /** 套餐列表（含窗口项）。 */
    public List<QuotaPlanDTO> list() {
        List<QuotaPlan> plans = quotaPlanMapper.selectList(
                new LambdaQueryWrapper<QuotaPlan>().orderByDesc(QuotaPlan::getCreatedAt));
        List<QuotaPlanItem> items = quotaPlanItemMapper.selectList(new LambdaQueryWrapper<QuotaPlanItem>());
        Map<String, List<QuotaPlanItem>> itemsByPlan = items.stream()
                .collect(Collectors.groupingBy(QuotaPlanItem::getPlanId));
        return plans.stream().map(plan -> toDTO(plan, itemsByPlan.get(plan.getId()))).toList();
    }

    /** 创建套餐（含窗口项）。 */
    @Transactional
    public QuotaPlanDTO create(SaveQuotaPlanCommand command) {
        QuotaPlan plan = new QuotaPlan();
        plan.setId(Ulid.next());
        plan.setPlanName(command.getPlanName());
        plan.setDescription(command.getDescription());
        plan.setStatus(command.getStatus() != null ? command.getStatus() : 1);
        plan.setCreatedBy(UserContext.currentUserId());
        quotaPlanMapper.insert(plan);
        insertItems(plan.getId(), command.getItems());
        log.info("创建配额套餐: id={}, name={}, windows={}", plan.getId(), plan.getPlanName(),
                command.getItems() == null ? 0 : command.getItems().size());
        return toDTO(plan, quotaPlanItemMapper.selectList(
                new LambdaQueryWrapper<QuotaPlanItem>().eq(QuotaPlanItem::getPlanId, plan.getId())));
    }

    /** 更新套餐（窗口项整体替换）。 */
    @Transactional
    public QuotaPlanDTO update(String planId, SaveQuotaPlanCommand command) {
        QuotaPlan plan = quotaPlanMapper.selectById(planId);
        if (plan == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "套餐不存在");
        }
        plan.setPlanName(command.getPlanName());
        plan.setDescription(command.getDescription());
        if (command.getStatus() != null) {
            plan.setStatus(command.getStatus());
        }
        quotaPlanMapper.updateById(plan);
        if (command.getItems() != null) {
            quotaPlanItemMapper.delete(new LambdaQueryWrapper<QuotaPlanItem>()
                    .eq(QuotaPlanItem::getPlanId, planId));
            insertItems(planId, command.getItems());
        }
        return toDTO(plan, quotaPlanItemMapper.selectList(
                new LambdaQueryWrapper<QuotaPlanItem>().eq(QuotaPlanItem::getPlanId, planId)));
    }

    /** 删除套餐（逻辑删除；已绑定用户自动视为无套餐）。 */
    @Transactional
    public void delete(String planId) {
        quotaPlanMapper.deleteById(planId);
        quotaPlanItemMapper.delete(new LambdaQueryWrapper<QuotaPlanItem>()
                .eq(QuotaPlanItem::getPlanId, planId));
        log.info("删除配额套餐: id={}", planId);
    }

    /** 绑定用户（失效旧生效绑定后写新绑定）。 */
    @Transactional
    public void bind(String userId, String planId) {
        QuotaPlan plan = quotaPlanMapper.selectById(planId);
        if (plan == null || plan.getStatus() == null || plan.getStatus() != 1) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "套餐不存在或未启用");
        }
        deactivateBindings(userId);
        UserQuotaBinding binding = new UserQuotaBinding();
        binding.setId(Ulid.next());
        binding.setUserId(userId);
        binding.setPlanId(planId);
        binding.setStatus(1);
        binding.setCreatedBy(UserContext.currentUserId());
        userQuotaBindingMapper.insert(binding);
        log.info("绑定用户套餐: user={}, plan={}", userId, planId);
    }

    /** 解绑用户（逻辑删除生效绑定）。 */
    @Transactional
    public void unbind(String userId) {
        deactivateBindings(userId);
        log.info("解绑用户套餐: user={}", userId);
    }

    /** 查询用户的生效绑定（无则 null）。 */
    public UserQuotaBinding findActiveBinding(String userId) {
        return userQuotaBindingMapper.selectList(new LambdaQueryWrapper<UserQuotaBinding>()
                        .eq(UserQuotaBinding::getUserId, userId)
                        .eq(UserQuotaBinding::getStatus, 1)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    /** 查询生效套餐（无绑定或套餐停用返回 null）。 */
    public QuotaPlan findActivePlanWithItems(String userId, List<QuotaPlanItem> itemsOut) {
        UserQuotaBinding binding = findActiveBinding(userId);
        if (binding == null) {
            return null;
        }
        QuotaPlan plan = quotaPlanMapper.selectById(binding.getPlanId());
        if (plan == null || plan.getStatus() == null || plan.getStatus() != 1) {
            return null;
        }
        itemsOut.addAll(quotaPlanItemMapper.selectList(new LambdaQueryWrapper<QuotaPlanItem>()
                .eq(QuotaPlanItem::getPlanId, plan.getId())));
        return plan;
    }

    private void deactivateBindings(String userId) {
        UserQuotaBinding live = findActiveBinding(userId);
        if (live != null) {
            live.setStatus(2);
            live.setUpdatedBy(UserContext.currentUserId());
            userQuotaBindingMapper.updateById(live);
        }
    }

    private void insertItems(String planId, List<QuotaWindowItemDTO> items) {
        if (items == null) {
            return;
        }
        for (QuotaWindowItemDTO item : items) {
            QuotaWindowType type = QuotaWindowType.parse(item.getWindowType());
            if (type == null || item.getTokenLimit() == null || item.getTokenLimit() <= 0) {
                throw BusinessException.of(ResultCode.PARAM_INVALID,
                        "窗口项非法: windowType=" + item.getWindowType() + ", tokenLimit=" + item.getTokenLimit());
            }
            QuotaPlanItem entity = new QuotaPlanItem();
            entity.setId(Ulid.next());
            entity.setPlanId(planId);
            entity.setWindowType(type.name());
            entity.setTokenLimit(item.getTokenLimit());
            quotaPlanItemMapper.insert(entity);
        }
    }

    private QuotaPlanDTO toDTO(QuotaPlan plan, List<QuotaPlanItem> items) {
        return QuotaPlanDTO.builder()
                .id(plan.getId())
                .planName(plan.getPlanName())
                .description(plan.getDescription())
                .status(plan.getStatus())
                .items(items == null ? List.of() : items.stream()
                        .map(i -> QuotaWindowItemDTO.builder()
                                .windowType(i.getWindowType())
                                .tokenLimit(i.getTokenLimit())
                                .build())
                        .toList())
                .build();
    }
}
