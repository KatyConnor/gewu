package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.session.Part;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PartMapper extends BaseMapper<Part> {
}
