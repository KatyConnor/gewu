package com.gewu.desktop.store;

import java.util.UUID;

/**
 * 本地 ID 生成（Phase 1：UUID-hex；ULID 对齐平台主键语义为后续优化项）。
 *
 * @since 1.0.0
 */
public final class Ids {

    private Ids() {
    }

    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
