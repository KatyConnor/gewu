package com.gewu.application.workspace.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Git 分支列表（本地 + 远程跟踪分支）。
 */
@Data
public class GitBranchesDTO {
    /** 当前分支 */
    private String current;
    private List<String> local = new ArrayList<>();
    private List<String> remote = new ArrayList<>();
}
