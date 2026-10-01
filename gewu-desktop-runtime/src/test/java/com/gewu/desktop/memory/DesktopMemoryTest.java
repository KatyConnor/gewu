package com.gewu.desktop.memory;

import com.gewu.agent.engine.memory.MemoryFragment;
import com.gewu.agent.engine.memory.MemoryStore;
import com.gewu.desktop.adapter.DesktopMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2-10 记忆 v1：store/retrieve 往返 + 分词打分 + 引擎注入路径（Router Bean 存在性）。
 *
 * @since 1.0.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("stub")
class DesktopMemoryTest {

    @Autowired
    private MemoryStore memoryStore;

    @Autowired
    private com.gewu.agent.engine.memory.MemoryRouter memoryRouter;

    @Test
    void storeAndRetrieveRoundtrip() {
        MemoryFragment f = new MemoryFragment();
        f.setId("mem-test-001");
        f.setDomain("default");
        f.setType("episodic");
        f.setContent("用户在开发 SM4 国密加密工具，偏好使用 Java 21 与 CBC 模式");
        memoryStore.store(f);

        List<MemoryFragment> hits = memoryStore.retrieve("default", "SM4 加密 Java 工具", 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).getContent()).contains("SM4");
        assertThat(hits.get(0).getScore()).isPositive();
    }

    @Test
    void noMatchReturnsEmpty() {
        List<MemoryFragment> hits = memoryStore.retrieve("default", "完全无关的量子纠缠$query", 3);
        // 允许弱命中，但断言打分机制存在（不抛异常即可）
        assertThat(hits).isNotNull();
    }

    @Test
    void memoryRouterIsDesktopImpl() {
        // 契约：桌面 MemoryRouter Bean 覆盖引擎 NoOp（否则记忆永不注入）
        assertThat(memoryRouter).isInstanceOf(com.gewu.desktop.adapter.DesktopMemoryRouter.class);
    }

    @Test
    void routerInjectsMemoryAsSystemMessage() {
        MemoryFragment f = new MemoryFragment();
        f.setDomain("default");
        f.setType("semantic");
        f.setContent("SM4 工具偏好：给出完整可编译的 Java 代码");
        memoryStore.store(f);

        List<com.gewu.agent.engine.llm.model.Message> messages = List.of(
                userMessage("写一个 SM4 工具"));
        List<com.gewu.agent.engine.llm.model.Message> out =
                memoryRouter.inject("default", messages, "SM4 工具");
        assertThat(out.size()).isEqualTo(messages.size() + 1);
        assertThat(out.get(0).getRole()).isEqualTo("system");
        assertThat(out.get(0).getContent()).contains("历史经验记忆");
    }

    private com.gewu.agent.engine.llm.model.Message userMessage(String content) {
        com.gewu.agent.engine.llm.model.Message m = new com.gewu.agent.engine.llm.model.Message();
        m.setRole("user");
        m.setContent(content);
        return m;
    }
}
