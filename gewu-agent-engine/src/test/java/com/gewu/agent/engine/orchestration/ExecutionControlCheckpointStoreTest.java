package com.gewu.agent.engine.orchestration;

import com.gewu.agent.engine.orchestration.model.OrchestrationContext;
import com.gewu.agent.engine.orchestration.model.OrchestrationGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检查点持久化联动测试（WFO-03）：
 * 暂停检查点双写（内存 + 持久层）、取出内存优先、进程重启后从持久层恢复、注销双清。
 */
class ExecutionControlCheckpointStoreTest {

    /** 内存 Map 实现的持久层替身（load 即删语义与真实实现约定一致） */
    private final Map<String, ExecutionControl.Checkpoint> persisted = new HashMap<>();

    private final OrchestrationCheckpointStore store = new OrchestrationCheckpointStore() {
        @Override
        public void save(String executionId, ExecutionControl.Checkpoint checkpoint) {
            persisted.put(executionId, checkpoint);
        }

        @Override
        public Optional<ExecutionControl.Checkpoint> load(String executionId) {
            return Optional.ofNullable(persisted.remove(executionId));
        }

        @Override
        public void delete(String executionId) {
            persisted.remove(executionId);
        }

        @Override
        public boolean has(String executionId) {
            return persisted.containsKey(executionId);
        }
    };

    private ExecutionControl.Checkpoint checkpoint(String resumeFrom) {
        OrchestrationGraph graph = OrchestrationGraph.builder().graphId("g-1").build();
        OrchestrationContext ctx = OrchestrationContext.builder().executionId("e-1").build();
        return new ExecutionControl.Checkpoint(graph, ctx, resumeFrom);
    }

    @Test
    @DisplayName("保存检查点双写内存与持久层")
    void saveCheckpointWritesBoth() {
        ExecutionControl control = new ExecutionControl(store);
        control.register("e-1");
        ExecutionControl.Checkpoint cp = checkpoint("n3");
        control.saveCheckpoint("e-1", cp);

        assertTrue(control.hasCheckpoint("e-1"));
        assertTrue(store.has("e-1"));
    }

    @Test
    @DisplayName("取出检查点内存优先，同时清除持久层")
    void takePrefersMemoryAndClearsPersistence() {
        ExecutionControl control = new ExecutionControl(store);
        control.register("e-1");
        ExecutionControl.Checkpoint cp = checkpoint("n3");
        control.saveCheckpoint("e-1", cp);

        Optional<ExecutionControl.Checkpoint> taken = control.takeCheckpoint("e-1");
        assertTrue(taken.isPresent());
        assertSame(cp, taken.get());
        // 取出后内存与持久层均清空，不可重复恢复
        assertFalse(control.hasCheckpoint("e-1"));
        assertFalse(store.has("e-1"));
    }

    @Test
    @DisplayName("进程重启场景：内存注册表为空时从持久层恢复检查点")
    void takeFallsBackToPersistenceAfterRestart() {
        ExecutionControl before = new ExecutionControl(store);
        before.saveCheckpoint("e-1", checkpoint("n3"));

        // 模拟进程重启：新的 ExecutionControl 实例（内存为空），持久层仍有检查点
        ExecutionControl after = new ExecutionControl(store);
        assertTrue(after.hasCheckpoint("e-1"));
        Optional<ExecutionControl.Checkpoint> taken = after.takeCheckpoint("e-1");
        assertTrue(taken.isPresent());
        assertEqualsResumeNode("n3", taken.get());
        assertFalse(store.has("e-1"));
    }

    @Test
    @DisplayName("注销执行同时清除内存与持久层检查点")
    void unregisterClearsBoth() {
        ExecutionControl control = new ExecutionControl(store);
        control.register("e-1");
        control.saveCheckpoint("e-1", checkpoint("n3"));

        control.unregister("e-1");
        assertFalse(control.hasCheckpoint("e-1"));
        assertFalse(store.has("e-1"));
    }

    @Test
    @DisplayName("持久层保存失败不中断暂停流程（内存检查点仍有效）")
    void persistenceFailureDoesNotBreakSave() {
        OrchestrationCheckpointStore failing = new OrchestrationCheckpointStore() {
            @Override
            public void save(String executionId, ExecutionControl.Checkpoint checkpoint) {
                throw new IllegalStateException("db down");
            }

            @Override
            public Optional<ExecutionControl.Checkpoint> load(String executionId) {
                return Optional.empty();
            }

            @Override
            public void delete(String executionId) {
                // no-op
            }

            @Override
            public boolean has(String executionId) {
                return false;
            }
        };
        ExecutionControl control = new ExecutionControl(failing);
        control.register("e-1");
        control.saveCheckpoint("e-1", checkpoint("n3"));

        assertTrue(control.hasCheckpoint("e-1"));
        assertTrue(control.takeCheckpoint("e-1").isPresent());
    }

    @Test
    @DisplayName("无参构造保持纯内存行为（默认 NOOP 持久层）")
    void defaultConstructorKeepsMemoryOnlyBehavior() {
        ExecutionControl control = new ExecutionControl();
        control.register("e-1");
        control.saveCheckpoint("e-1", checkpoint("n3"));
        assertTrue(control.hasCheckpoint("e-1"));
        assertTrue(control.takeCheckpoint("e-1").isPresent());
        assertFalse(control.hasCheckpoint("e-1"));
    }

    private void assertEqualsResumeNode(String expected, ExecutionControl.Checkpoint actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual.resumeFromNodeId());
    }
}
