package com.gewu.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.domain.project.ProjectMember;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectMemberMapper extends BaseMapper<ProjectMember> {

    /** 物理清理项目下已逻辑删除的成员行：绕过 @TableLogic，避免成员重新分配时撞 uk_project_member 唯一键 */
    @Delete("DELETE FROM project_member WHERE project_id = #{projectId} AND deleted = 1")
    int purgeDeletedByProjectId(@Param("projectId") String projectId);
}
