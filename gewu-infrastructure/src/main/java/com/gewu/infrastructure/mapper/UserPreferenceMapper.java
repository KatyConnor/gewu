package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.usage.UserPreference;
import org.apache.ibatis.annotations.Mapper;

/**
 * UserPreference Mapper。
 *
 * @since 1.0.0
 */
@Mapper
public interface UserPreferenceMapper extends BaseMapper<UserPreference> {
}
