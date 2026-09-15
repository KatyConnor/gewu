package com.gewu.application.wenshi.knowledge;

import com.gewu.domain.wenshi.knowledge.ProceduralMemory;
import com.gewu.infrastructure.mapper.wenshi.ProceduralMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 程序记忆服务 — 管理工具、SOP（标准操作流程）、技能的程序化知识存储与检索。
 * <p>
 * 程序记忆用于存储"怎么做"的知识，包括：
 * <ul>
 *   <li>TOOL — 可调用的工具定义</li>
 *   <li>SOP — 标准操作流程</li>
 *   <li>SKILL — 从交互中学习到的技能</li>
 * </ul>
 * 支持按名称精确查找和按类型批量检索，并提供使用次数统计能力。
 *
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProceduralMemoryService {

    private final ProceduralMemoryMapper mapper;

    /**
     * 注册一个工具到程序记忆。
     * <p>
     * 工具类型固定为 {@code TOOL}，初始熟练度为 1，状态为启用。
     *
     * @param tenantId    租户 ID
     * @param name        工具名称（唯一标识）
     * @param description 工具功能描述
     * @param definition  工具定义（如参数 schema）
     * @return 持久化后的 {@link ProceduralMemory} 实体
     * @since 1.0.0
     */
    public ProceduralMemory registerTool(String tenantId, String name, String description, String definition) {
        ProceduralMemory memory = new ProceduralMemory();
        memory.setId(com.gewu.common.ulid.Ulid.next());
        memory.setTenantId(tenantId);
        memory.setType("TOOL");
        memory.setName(name);
        memory.setDescription(description);
        memory.setDefinition(definition);
        memory.setSkillLevel(1);
        memory.setStatus(1);

        mapper.insert(memory);
        log.info("ProceduralMemoryService.registerTool: id={}, name={}", memory.getId(), name);
        return memory;
    }

    /**
     * 注册一个标准操作流程（SOP）到程序记忆。
     * <p>
     * 类型固定为 {@code SOP}，初始熟练度为 1，状态为启用。
     *
     * @param tenantId    租户 ID
     * @param name        SOP 名称
     * @param description SOP 描述
     * @param definition  SOP 详细步骤定义
     * @return 持久化后的 {@link ProceduralMemory} 实体
     * @since 1.0.0
     */
    public ProceduralMemory registerSOP(String tenantId, String name, String description, String definition) {
        ProceduralMemory memory = new ProceduralMemory();
        memory.setId(com.gewu.common.ulid.Ulid.next());
        memory.setTenantId(tenantId);
        memory.setType("SOP");
        memory.setName(name);
        memory.setDescription(description);
        memory.setDefinition(definition);
        memory.setSkillLevel(1);
        memory.setStatus(1);

        mapper.insert(memory);
        log.info("ProceduralMemoryService.registerSOP: id={}, name={}", memory.getId(), name);
        return memory;
    }

    /**
     * 注册一个从交互中学习到的技能。
     * <p>
     * 类型固定为 {@code SKILL}，可记录技能来源（learnedFrom），初始熟练度为 1。
     *
     * @param tenantId    租户 ID
     * @param name        技能名称
     * @param description 技能描述
     * @param definition  技能定义
     * @param learnedFrom 技能学习来源（如会话 ID 或场景描述）
     * @return 持久化后的 {@link ProceduralMemory} 实体
     * @since 1.0.0
     */
    public ProceduralMemory registerSkill(String tenantId, String name, String description, String definition, String learnedFrom) {
        ProceduralMemory memory = new ProceduralMemory();
        memory.setId(com.gewu.common.ulid.Ulid.next());
        memory.setTenantId(tenantId);
        memory.setType("SKILL");
        memory.setName(name);
        memory.setDescription(description);
        memory.setDefinition(definition);
        memory.setSkillLevel(1);
        memory.setLearnedFrom(learnedFrom);
        memory.setStatus(1);

        mapper.insert(memory);
        log.info("ProceduralMemoryService.registerSkill: id={}, name={}", memory.getId(), name);
        return memory;
    }

    /**
     * 按名称精确查找有效的程序记忆。
     * <p>
     * 仅返回状态为启用（status=1）的记录。
     *
     * @param tenantId 租户 ID
     * @param name     记忆名称
     * @return 匹配的 {@link ProceduralMemory}，未找到返回 null
     * @since 1.0.0
     */
    public ProceduralMemory findByName(String tenantId, String name) {
        return mapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProceduralMemory>()
                        .eq(ProceduralMemory::getTenantId, tenantId)
                        .eq(ProceduralMemory::getName, name)
                        .eq(ProceduralMemory::getStatus, 1)
        );
    }

    /**
     * 按类型批量检索有效的程序记忆。
     * <p>
     * 结果按熟练度降序排列，便于优先推荐高熟练度的记忆项。
     *
     * @param tenantId 租户 ID
     * @param type     记忆类型（TOOL / SOP / SKILL）
     * @return 符合条件的记忆列表，按熟练度降序
     * @since 1.0.0
     */
    public List<ProceduralMemory> findByType(String tenantId, String type) {
        return mapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProceduralMemory>()
                        .eq(ProceduralMemory::getTenantId, tenantId)
                        .eq(ProceduralMemory::getType, type)
                        .eq(ProceduralMemory::getStatus, 1)
                        .orderByDesc(ProceduralMemory::getSkillLevel)
        );
    }

    /**
     * 检索指定租户下所有技能类型的程序记忆。
     * <p>
     * 等价于 {@code findByType(tenantId, "SKILL")}。
     *
     * @param tenantId 租户 ID
     * @return 技能列表，按熟练度降序
     * @since 1.0.0
     */
    public List<ProceduralMemory> findSkills(String tenantId) {
        return findByType(tenantId, "SKILL");
    }

    /**
     * 记录一次程序记忆的使用情况，累加使用次数。
     * <p>
     * 调用方应在工具/SOP 执行成功后调用此方法，用于后续熟练度评估。
     *
     * @param memoryId 程序记忆 ID
     * @param success  本次使用是否成功（当前实现仅累加次数，不区分成功/失败）
     * @since 1.0.0
     */
    public void recordUsage(String memoryId, boolean success) {
        ProceduralMemory memory = mapper.selectById(memoryId);
        if (memory != null) {
            memory.setUsageCount((memory.getUsageCount() != null ? memory.getUsageCount() : 0) + 1);
            mapper.updateById(memory);
        }
    }
}
