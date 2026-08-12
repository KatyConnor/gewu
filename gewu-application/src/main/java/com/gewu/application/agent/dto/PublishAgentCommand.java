package com.gewu.application.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发布智能体到广场命令.
 */
@Data
public class PublishAgentCommand {

    @NotBlank(message = "Agent ID 不能为空")
    private String agentId;

    @Size(max = 16, message = "emoji 最长 16 个字符")
    private String emoji;

    @Size(max = 64, message = "分类最长 64 个字符")
    private String category;

    private List<String> tags;
}
