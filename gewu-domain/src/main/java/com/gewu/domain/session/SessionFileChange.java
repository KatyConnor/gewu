package com.gewu.domain.session;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 会话文件变更记录（S9 F3）——写类文件工具首次修改路径前记录 before 快照，
 * 供右侧文件编辑面板的变更列表与 diff 审查。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("session_file_change")
public class SessionFileChange extends BaseEntity {

    /** 所属会话 */
    private String sessionId;
    /** 操作用户 */
    private String userId;
    /** 相对工作空间根目录的文件路径 */
    private String filePath;
    /** 变更类型：CREATE / MODIFY / DELETE */
    private String changeType;
    /** 首次修改前内容快照（diff 基线，新增文件为 NULL） */
    private String beforeSnapshot;
}
