package com.gewu.domain.orchestration;

import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 编排图定时触发配置（WFC-02）- 每图一条。
 * <p>调度器扫描 next_fire_at 到期行，CAS 抢占后以系统身份发起执行；
 * 启停由 enabled 控制，重算 next_fire_at 由触发器完成。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("orchestration_schedule")
public class OrchestrationScheduleEntity extends BaseEntity {

    /** 编排图 ID（唯一） */
    private String graphId;
    /** Cron 表达式（Spring CronExpression，6 位） */
    private String cronExpr;
    /** 时区 */
    private String timezone;
    /** 执行输入模板（原样作为 input） */
    private String inputTemplate;
    /** 启停开关 */
    private Integer enabled;
    /** 上次触发时间（毫秒） */
    private Long lastFireAt;
    /** 下次触发时间（毫秒，CAS 抢占键） */
    private Long nextFireAt;
}
