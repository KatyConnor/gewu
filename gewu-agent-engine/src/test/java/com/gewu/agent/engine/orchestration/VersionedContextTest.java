package com.gewu.agent.engine.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link VersionedContext} copy-on-write 版本管理测试。
 */
@DisplayName("版本化上下文")
class VersionedContextTest {

    @Test
    @DisplayName("初始变量进入版本 0")
    void initialVariables() {
        VersionedContext ctx = new VersionedContext(Map.of("input", "需求文档"));
        assertThat(ctx.getVersion()).isZero();
        assertThat(ctx.getVariable("input")).isEqualTo("需求文档");
        assertThat(ctx.getVersionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("putVariable 生成新版本且旧版本快照不被修改（copy-on-write）")
    void putVariableCreatesNewVersion() {
        VersionedContext ctx = new VersionedContext(Map.of("a", 1));
        Map<String, Object> v0Snapshot = ctx.snapshot();

        int newVersion = ctx.putVariable("b", 2);

        assertThat(newVersion).isEqualTo(1);
        assertThat(ctx.getVersion()).isEqualTo(1);
        assertThat(ctx.getVariable("a")).isEqualTo(1);
        assertThat(ctx.getVariable("b")).isEqualTo(2);
        // 旧版本快照不受写入影响
        assertThat(v0Snapshot).doesNotContainKey("b");
        assertThat(ctx.getSnapshot(0)).doesNotContainKey("b");
    }

    @Test
    @DisplayName("同 key 覆盖写入同样生成新版本")
    void overwriteCreatesNewVersion() {
        VersionedContext ctx = new VersionedContext(Map.of("a", 1));
        ctx.putVariable("a", 2);
        ctx.putVariable("a", 3);

        assertThat(ctx.getVersion()).isEqualTo(2);
        assertThat(ctx.getVariable("a")).isEqualTo(3);
        assertThat(ctx.getSnapshot(1).get("a")).isEqualTo(2);
        assertThat(ctx.getSnapshot(2).get("a")).isEqualTo(3);
    }

    @Test
    @DisplayName("rollback 回滚到任意历史版本")
    void rollbackToVersion() {
        VersionedContext ctx = new VersionedContext(Map.of("a", 1));
        ctx.putVariable("a", 2);
        ctx.putVariable("a", 3);

        ctx.rollback(1);

        assertThat(ctx.getVersion()).isEqualTo(1);
        assertThat(ctx.getVariable("a")).isEqualTo(2);
        // 回滚后继续写入从该版本派生
        ctx.putVariable("b", 9);
        assertThat(ctx.getVersion()).isEqualTo(3);
        assertThat(ctx.getVariable("a")).isEqualTo(2);
    }

    @Test
    @DisplayName("rollbackOne 回退一步，版本 0 时返回 false")
    void rollbackOne() {
        VersionedContext ctx = new VersionedContext();
        assertThat(ctx.rollbackOne()).isFalse();

        ctx.putVariable("a", 1);
        ctx.putVariable("a", 2);
        assertThat(ctx.rollbackOne()).isTrue();
        assertThat(ctx.getVariable("a")).isEqualTo(1);
    }

    @Test
    @DisplayName("无效版本号抛 IllegalArgumentException")
    void invalidVersionThrows() {
        VersionedContext ctx = new VersionedContext();
        ctx.putVariable("a", 1);
        assertThatThrownBy(() -> ctx.rollback(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ctx.rollback(5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ctx.getSnapshot(5)).isNull();
    }

    @Test
    @DisplayName("snapshot 返回不可变视图")
    void snapshotImmutable() {
        VersionedContext ctx = new VersionedContext(Map.of("a", 1));
        Map<String, Object> snapshot = ctx.snapshot();
        assertThatThrownBy(() -> snapshot.put("b", 2))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
