package com.veloflow.engine.commons;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.io.Serializable;

/** Veloflow 简化实体基类（无逻辑删除，如通知/权限矩阵类轻表） */
@Data
public abstract class BaseSimpleEntity implements Serializable {

    @TableId(type = IdType.INPUT)
    private String id;

    @TableField(fill = FieldFill.INSERT)
    private Long createdAt;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
}
