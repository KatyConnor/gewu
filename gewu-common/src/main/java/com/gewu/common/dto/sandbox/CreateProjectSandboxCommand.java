package com.gewu.common.dto.sandbox;

import lombok.Data;

@Data
public class CreateProjectSandboxCommand {

    private String template;

    private String sandboxName;
}