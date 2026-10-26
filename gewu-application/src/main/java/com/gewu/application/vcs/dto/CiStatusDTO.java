package com.gewu.application.vcs.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * CI 状态聚合（跨平台归一）。
 */
@Data
public class CiStatusDTO {
    /** none / pending / success / failure / canceled */
    private String overall;
    private String ref;
    private String webUrl;
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item {
        /** 上下文名（GitHub context / GitLab pipeline id） */
        private String name;
        private String state;
        private String webUrl;
    }
}
