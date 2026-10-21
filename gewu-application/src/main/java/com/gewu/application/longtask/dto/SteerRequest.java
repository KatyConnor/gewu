package com.gewu.application.longtask.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 长任务补充指令（写入下一段种子提示，用户实报：期间可随时纠偏） */
@Data
public class SteerRequest {

    @NotBlank(message = "补充指令不能为空")
    private String content;
}
