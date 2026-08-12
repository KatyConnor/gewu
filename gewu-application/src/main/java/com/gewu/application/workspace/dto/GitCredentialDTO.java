package com.gewu.application.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

/**
 * Git 凭证 DTO（列表展示用，不含明文）.
 */
@Data
@Builder
public class GitCredentialDTO {

    private String credentialId;
    private String credName;
    private String credType;
    private String gitHost;
    private Boolean hasPublicKey;
    private Long createdAt;
}
