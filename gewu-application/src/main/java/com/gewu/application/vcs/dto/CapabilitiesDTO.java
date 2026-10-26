package com.gewu.application.vcs.dto;

import lombok.Data;

/**
 * 平台能力清单（前端门控依据；能力 = 平台静态支持 ∩ 凭证类型 ∩ 自建版本）。
 */
@Data
public class CapabilitiesDTO {
    private String platform;
    /** 评审批准/驳回写操作 */
    private boolean reviewWrite;
    /** Deploy Key 注册（SSH 免密拉取闭环） */
    private boolean deployKey;
    /** Release 附件上传 */
    private boolean releaseAsset;
}
