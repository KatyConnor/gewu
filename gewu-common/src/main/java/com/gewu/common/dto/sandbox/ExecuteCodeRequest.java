package com.gewu.common.dto.sandbox;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class ExecuteCodeRequest {

    @NotBlank(message = "编程语言不能为空")
    private String language;

    @NotBlank(message = "代码不能为空")
    private String code;

    @Min(5)
    @Max(300)
    private Integer timeout;

    /**
     * 可选：关联的用户工作空间 ID。携带时执行容器挂载对应持久卷到 /workspace
     * （DockerSandboxProvider 按 workspaceId 挂 gewu-ws-* 命名卷），执行期间写入
     * /workspace 的文件随卷持久化，可被会话文件工具与 dev 沙箱读到；容器本身仍即焚。
     * 缺省保持原有行为（无卷、完全隔离的临时容器）。
     */
    private String workspaceId;
}