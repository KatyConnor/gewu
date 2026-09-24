package com.gewu.admin.dto.quota;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 套餐窗口项（创建/更新请求与展示共用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaWindowItemDTO {

    /** 窗口类型: FIVE_HOUR / WEEK / MONTH / QUARTER */
    private String windowType;

    /** 窗口内 token 总量上限 */
    private Long tokenLimit;
}
