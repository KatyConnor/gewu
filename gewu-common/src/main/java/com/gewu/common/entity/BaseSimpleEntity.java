package com.gewu.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;

/**
 * 轻量级实体基类 — 仅有主键和时间戳，无逻辑删除和乐观锁.
 *
 * <p>用于关联表、日志表等不需要逻辑删除的表。
 */
@Data
public abstract class BaseSimpleEntity implements Serializable {

    @TableId(type = IdType.INPUT)
    private String id;

    @TableField(fill = FieldFill.INSERT)
    private Long createdAt;
}