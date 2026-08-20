package com.gewu.agent.engine.orchestration;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

/**
 * 不可变状态管理器 - copy-on-write 版本追踪。
 * <p>每次 {@link #putVariable} 生成新版本（深拷贝当前状态），支持回溯与审计。
 * {@link #rollback} 可回滚到任意历史版本。
 *
 * @since 1.0.0
 */
@Slf4j
public class VersionedContext {

    private final List<Map<String, Object>> versions;
    private int current;

    public VersionedContext() {
        this.versions = new ArrayList<>();
        this.versions.add(new HashMap<>());
        this.current = 0;
    }

    public VersionedContext(Map<String, Object> initial) {
        this.versions = new ArrayList<>();
        this.versions.add(new HashMap<>(initial != null ? initial : Map.of()));
        this.current = 0;
    }

    /**
     * 获取当前版本的状态值。
     */
    public Object getVariable(String key) {
        return versions.get(current).get(key);
    }

    /**
     * 写入变量（copy-on-write，生成新版本）。
     *
     * @return 新版本号
     */
    public int putVariable(String key, Object value) {
        Map<String, Object> newVersion = new HashMap<>(versions.get(current));
        newVersion.put(key, value);
        versions.add(newVersion);
        current = versions.size() - 1;
        log.debug("VersionedContext.putVariable: key={}, version={}", key, current);
        return current;
    }

    /**
     * 获取当前版本的不可变快照。
     */
    public Map<String, Object> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(versions.get(current)));
    }

    /**
     * 回滚到指定版本。
     */
    public void rollback(int version) {
        if (version < 0 || version >= versions.size()) {
            throw new IllegalArgumentException("无效版本号: " + version + ", 范围: 0-" + (versions.size() - 1));
        }
        log.info("VersionedContext.rollback: from={} to={}", current, version);
        current = version;
    }

    /**
     * 回滚到上一版本。
     */
    public boolean rollbackOne() {
        if (current > 0) {
            current--;
            log.debug("VersionedContext.rollbackOne: to={}", current);
            return true;
        }
        return false;
    }

    /**
     * 获取当前版本号。
     */
    public int getVersion() {
        return current;
    }

    /**
     * 获取总版本数。
     */
    public int getVersionCount() {
        return versions.size();
    }

    /**
     * 获取指定版本的快照。
     */
    public Map<String, Object> getSnapshot(int version) {
        if (version < 0 || version >= versions.size()) return null;
        return Collections.unmodifiableMap(versions.get(version));
    }

    /**
     * 获取当前版本的变量 Map（可变副本，供兼容接口使用）。
     */
    public Map<String, Object> asMap() {
        return versions.get(current);
    }
}