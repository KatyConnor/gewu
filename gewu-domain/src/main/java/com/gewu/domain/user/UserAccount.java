package com.gewu.domain.user;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_account")
public class UserAccount extends BaseEntity {

    private String username;
    private String email;
    private String phone;
    private String passwordHash;
    private String passwordSalt;
    private String displayName;
    private String avatarUrl;
    private Integer status;
    private Long lastLoginAt;
    private String lastLoginIp;
    private Integer loginFailCount;
    private Long lockedUntil;
}