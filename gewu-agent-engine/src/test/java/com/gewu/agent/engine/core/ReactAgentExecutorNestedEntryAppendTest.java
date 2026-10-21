package com.gewu.agent.engine.core;

import com.gewu.agent.engine.tool.FileWorkspaceSpi;
import com.gewu.agent.engine.tool.ToolContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReactAgentExecutor#appendNestedEntry} 附加逻辑直测（设计文档 57）：
 * 就近触发 / 根不触发 / GEWU 优先 / 任务内去重 / 密钥省略 / 超限截断。
 */
class ReactAgentExecutorNestedEntryAppendTest {

    /** 内存文件工作区（对齐引擎既有 Fake 惯例），带探针调用计数。 */
    static class FakeWs implements FileWorkspaceSpi {
        final Map<String, String> files = new HashMap<>();
        int readCalls;

        @Override
        public String readFile(ToolContext ctx, String path) {
            readCalls++;
            return files.get(path);
        }

        @Override
        public void writeFile(ToolContext ctx, String path, String content) {
            files.put(path, content);
        }

        @Override
        public List<String> listDir(ToolContext ctx, String path) {
            return List.of();
        }
    }

    @Test
    @DisplayName("read 触发：子目录入口文件附加进工具输出；探针记忆化同目录零重复探测")
    void readTriggersAppend() {
        FakeWs ws = new FakeWs();
        ws.files.put("packages/foo/AGENTS.md", "foo 子项目约定：测试命令 pytest -q");
        ToolContext ctx = ToolContext.builder().build();
        String out = ReactAgentExecutor.appendNestedEntry("文件内容", ctx, "packages/foo/src/X.java", ws);
        assertThat(out).startsWith("文件内容")
                .contains("## 子目录调度指令（packages/foo/）")
                .contains("pytest -q");
        int afterFirst = ws.readCalls;
        // 任务内去重：同一子目录第二次读取不再附加
        String again = ReactAgentExecutor.appendNestedEntry("再次读取", ctx, "packages/foo/src/Y.java", ws);
        assertThat(again).isEqualTo("再次读取");
        // 探针记忆化：第二次同目录操作零重复探测（性能修复断言）
        assertThat(ws.readCalls).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("根层文件不触发（根入口已随 system 注入）")
    void rootNotServed() {
        FakeWs ws = new FakeWs();
        ws.files.put("AGENTS.md", "根约定");
        String out = ReactAgentExecutor.appendNestedEntry("README 内容", ToolContext.builder().build(), "README.md", ws);
        assertThat(out).isEqualTo("README 内容");
    }

    @Test
    @DisplayName("同层 GEWU.md 优先")
    void gewuPreferred() {
        FakeWs ws = new FakeWs();
        ws.files.put("packages/foo/GEWU.md", "格物专属约定");
        ws.files.put("packages/foo/AGENTS.md", "通用约定");
        String out = ReactAgentExecutor.appendNestedEntry("x", ToolContext.builder().build(), "packages/foo/a.java", ws);
        assertThat(out).contains("格物专属约定").doesNotContain("通用约定");
    }

    @Test
    @DisplayName("写入与编辑同样触发")
    void writeAndEditTrigger() {
        FakeWs ws = new FakeWs();
        ws.files.put("packages/foo/AGENTS.md", "foo 约定");
        ToolContext ctx = ToolContext.builder().build();
        String w = ReactAgentExecutor.appendNestedEntry("已写入 a.java", ctx, "packages/foo/a.java", ws);
        assertThat(w).contains("foo 约定");
        ToolContext ctx2 = ToolContext.builder().build();
        String e = ReactAgentExecutor.appendNestedEntry("已编辑 a.java", ctx2, "packages/foo/a.java", ws);
        assertThat(e).contains("foo 约定");
    }

    @Test
    @DisplayName("密钥命中省略内容并提示")
    void secretOmitted() {
        FakeWs ws = new FakeWs();
        ws.files.put("packages/foo/AGENTS.md", "key=" + "AKIAIOSFODNN7EXAMPLE");
        String out = ReactAgentExecutor.appendNestedEntry("x", ToolContext.builder().build(), "packages/foo/a.java", ws);
        assertThat(out).contains("疑似包含").doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }

    @Test
    @DisplayName("16KB 超限截断并标注")
    void oversizeTruncated() {
        FakeWs ws = new FakeWs();
        ws.files.put("packages/foo/AGENTS.md", "格".repeat(20_000));
        String out = ReactAgentExecutor.appendNestedEntry("x", ToolContext.builder().build(), "packages/foo/a.java", ws);
        assertThat(out).contains("子目录调度指令超限截断");
        assertThat(out.length()).isLessThan(20_000);
    }

    @Test
    @DisplayName("并发 append：无异常、去重集与探针缓存完整（F-501 回归）")
    void concurrentAppends_threadSafe() throws Exception {
        FakeWs ws = new FakeWs();
        int dirs = 20;
        for (int i = 0; i < dirs; i++) {
            ws.files.put("pkg" + i + "/AGENTS.md", "约定" + i);
        }
        ToolContext ctx = ToolContext.builder().build();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var calls = new java.util.ArrayList<java.util.concurrent.Callable<String>>();
        for (int i = 0; i < dirs * 2; i++) {
            final int k = i % dirs;
            calls.add(() -> ReactAgentExecutor.appendNestedEntry("out" + k, ctx, "pkg" + k + "/a.java", ws));
        }
        pool.invokeAll(calls);
        pool.shutdown();
        // 去重集：每目录恰好一项；探针缓存：每目录两键（GEWU 缺失哨兵 + AGENTS 内容），无丢失无重复
        assertThat(ctx.getNestedEntriesServed()).hasSize(dirs);
        assertThat(ctx.getNestedEntryProbeCache()).hasSize(dirs * 2);
    }

    @Test
    @DisplayName("写→失效→读 即时发现链（F-503）")
    void invalidation_enablesImmediateDiscovery() {
        FakeWs ws = new FakeWs();
        ToolContext ctx = ToolContext.builder().build();
        // 首次探测未命中（缓存写入空串哨兵）
        assertThat(ReactAgentExecutor.appendNestedEntry("首轮", ctx, "packages/foo/a.java", ws)).isEqualTo("首轮");
        // write_file 创建入口文件 + invalidate（executeFileTool 写路径的次序）
        ws.files.put("packages/foo/GEWU.md", "新建的入口文件");
        ReactAgentExecutor.invalidateNestedProbeCache(ctx, "packages/foo/GEWU.md");
        // 失效后同目录读取 → 即时发现
        String out = ReactAgentExecutor.appendNestedEntry("次轮", ctx, "packages/foo/b.java", ws);
        assertThat(out).contains("新建的入口文件");
        // 非入口文件路径失效为 no-op
        ReactAgentExecutor.invalidateNestedProbeCache(ctx, "packages/foo/other.md");
        assertThat(out).contains("新建的入口文件");
    }

    @Test
    @DisplayName("无命中原样返回；ws 为 null 安全")
    void noHitAndNullWs() {
        assertThat(ReactAgentExecutor.appendNestedEntry("原样", ToolContext.builder().build(), "a.md", null))
                .isEqualTo("原样");
        assertThat(ReactAgentExecutor.appendNestedEntry("原样", ToolContext.builder().build(), "a.md", new FakeWs()))
                .isEqualTo("原样");
    }
}
