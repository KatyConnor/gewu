package com.gewu.application.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建目录命令.
 */
@Data
public class CreateDirCommand {

    /** 父目录ID，null=根目录 */
    private String parentId;

    @NotBlank(message = "目录名不能为空")
    @Size(max = 255, message = "目录名最长 255 字符")
    private String dirName;
}
