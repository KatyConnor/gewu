package com.gewu.application.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重命名/移动文件命令.
 */
@Data
public class RenameFileCommand {

    @NotBlank(message = "文件名不能为空")
    @Size(max = 255, message = "文件名最长 255 字符")
    private String fileName;

    /** 目标父目录ID，null=根目录。不传则仅重命名不移动 */
    private String parentId;
}
