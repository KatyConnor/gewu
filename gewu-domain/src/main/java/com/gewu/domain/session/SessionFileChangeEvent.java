package com.gewu.domain.session;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 会话文件写入事件（文件变更统计修复）：每次对文件的写入累计一行增删统计，
 * 会话级变更列表按路径求和得到 git churn 语义的累计增删行数；
 * 回合撤销按 turn_seq 精确删除对应回合的事件。
 */
@Data
@TableName("session_file_change_event")
public class SessionFileChangeEvent {

    private String id;
    private String sessionId;
    private String filePath;
    /** 产生本次写入的回合序号（0=回合外写入，如面板手工保存）；回合撤销按此精确回退 */
    private Long turnSeq;
    private Integer additions;
    private Integer deletions;
    private Long createdAt;
}
