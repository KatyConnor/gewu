package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.workspace.GitCredential;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GitCredentialMapper extends BaseMapper<GitCredential> {
}
