package com.gewu.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 项目编号生成器（管理端副本）.
 *
 * <p>编号规则与 gewu-application 的 ProjectCodeGenerator 一致：
 * P + yyyyMMdd(8位) + P + 机器编号(3位) + 顺序号(5位)，示例 P20260804P00100001。
 * <p>两服务共用同一 Redis key 前缀（gewu:project:code:seq:）保证跨服务不重号；
 * 本模块不可依赖 gewu-application，故独立成副本，改动编号规则需两处同步。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectCodeGenerator {

    private static final String KEY_PREFIX = "gewu:project:code:seq:";
    private static final Duration KEY_TTL = Duration.ofHours(25);

    private final StringRedisTemplate redisTemplate;

    @Value("${gewu.machine-id:-1}")
    private int machineId;

    /**
     * 生成项目编号.
     *
     * @return 格式为 P{yyyyMMdd}P{3位机器编号}{5位顺序号} 的项目编号
     */
    public String generate() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String redisKey = KEY_PREFIX + dateStr;

        Long seq = redisTemplate.opsForValue().increment(redisKey);
        if (seq != null && seq == 1L) {
            redisTemplate.expire(redisKey, KEY_TTL);
        }

        if (seq == null) {
            log.warn("Redis 不可用，使用时间戳回退生成项目编号顺序号");
            seq = System.currentTimeMillis() % 100000L;
        }

        int resolvedMachineId = machineId >= 0 ? machineId : resolveMachineId();

        return String.format("P%sP%03d%05d", dateStr, resolvedMachineId, seq);
    }

    /**
     * 自动计算机器编号：hostname hash % 1000.
     */
    private int resolveMachineId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            return Math.abs(hostname.hashCode()) % 1000;
        } catch (Exception e) {
            log.warn("获取 hostname 失败，使用默认机器编号 0: {}", e.getMessage());
            return 0;
        }
    }
}
