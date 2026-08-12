package com.gewu.domain.workspace;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("git_credential")
public class GitCredential extends BaseEntity {

    private String userId;
    private String credName;
    /** ssh_key / token */
    private String credType;
    /** SM4 加密后的凭证内容 */
    private String credValue;
    private String sshPublicKey;
    private String gitHost;
}
