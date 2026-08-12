package com.gewu.domain.requirement;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("requirement_file")
public class RequirementFile extends BaseEntity {

    private String requirementId;
    /** 文件分类: docs/tests/reports */
    private String category;
    private String fileName;
    /** MinIO 对象 Key */
    private String objectKey;
    private String mimeType;
    private Long fileSize;
    private Integer version;
}
