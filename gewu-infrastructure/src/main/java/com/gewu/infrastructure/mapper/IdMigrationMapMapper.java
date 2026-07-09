package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.migration.IdMigrationMap;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdMigrationMapMapper extends BaseMapper<IdMigrationMap> {
}
