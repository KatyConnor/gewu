package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.gewu.domain.skill.Skill;

@Mapper
public interface SkillMapper extends BaseMapper<Skill> {
}
