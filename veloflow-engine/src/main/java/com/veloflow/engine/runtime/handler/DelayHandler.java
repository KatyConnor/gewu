package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.runtime.WorkflowNodeContext;
import com.veloflow.engine.runtime.WorkflowNodeHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 延时等待节点（等待型，51 号 §二 2.7）：激活即登记 timeout_at，
 * 由 WorkflowTimerRunner 定时扫描到期后驱动完成——进程重启不丢延时。
 */
@Component
public class DelayHandler implements WorkflowNodeHandler {

    /** 支持的时间单位 → 毫秒 */
    private static final Map<String, Long> UNIT_MILLIS = Map.of(
            "seconds", 1000L, "minutes", 60_000L, "hours", 3_600_000L);

    @Override
    public String type() {
        return "delay";
    }

    @Override
    public NodeKind kind() {
        return NodeKind.WAITING;
    }

    @Override
    public List<String> requiredConfigFields() {
        return List.of("duration");
    }

    @Override
    public void activate(WorkflowNodeContext context) {
        long duration = parseLong(context.config().get("duration"), 30);
        String unit = String.valueOf(context.config().getOrDefault("unit", "seconds")).toLowerCase();
        long millis = duration * UNIT_MILLIS.getOrDefault(unit, 1000L);
        // 登记 timeout_at（调度器在激活尾部统一落库）；到期由 TimerRunner 驱动完成
        context.nodeInstance().setTimeoutAt(System.currentTimeMillis() + millis);
    }

    private long parseLong(Object value, long defaultValue) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 非数字按缺省
            }
        }
        return defaultValue;
    }
}
