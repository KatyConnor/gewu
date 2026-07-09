package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.project.ProjectDirectory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectDirectoryMapper extends BaseMapper<ProjectDirectory> {
}
