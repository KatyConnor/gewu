package com.gewu.domain.migration;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("id_migration_map")
public class IdMigrationMap extends BaseSimpleEntity {

    private String sourceType;
    private String sourceId;
    private String targetId;
}