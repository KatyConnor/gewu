package com.gewu.domain.session;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseSimpleEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("part")
public class Part extends BaseSimpleEntity {

    private String messageId;
    private String sessionId;
    private String partType;
    private String data;
    private Integer sortOrder;
    private Long timeUpdated;
}