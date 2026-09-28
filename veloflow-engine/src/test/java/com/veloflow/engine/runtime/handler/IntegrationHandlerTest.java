package com.veloflow.engine.runtime.handler;

import com.veloflow.engine.ai.FlowDataSourceBridge;
import com.veloflow.engine.ai.FlowMailBridge;
import com.veloflow.engine.persistence.model.WorkflowInstance;
import com.veloflow.engine.runtime.WorkflowNodeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集成节点测试（53 号 §3.6，P6 二期）：database SQL 守卫（只读红线）、
 * counter 计数、email/im-notify 未接入时可读失败。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IntegrationHandlerTest {

    @Mock FlowDataSourceBridge dataSourceBridge;
    @Mock FlowMailBridge mailBridge;
    @Mock WorkflowNodeContext context;
    @Mock WorkflowInstance instance;
    @Mock Environment environment;

    private DatabaseHandler databaseHandler;
    private EmailHandler emailHandler;
    private ImNotifyHandler imNotifyHandler;
    private CounterHandler counterHandler;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FlowDataSourceBridge> dbProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        lenient().when(dbProvider.getIfAvailable()).thenReturn(dataSourceBridge);
        databaseHandler = new DatabaseHandler(dbProvider);
        @SuppressWarnings("unchecked")
        ObjectProvider<FlowMailBridge> mailProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        lenient().when(mailProvider.getIfAvailable()).thenReturn(mailBridge);
        emailHandler = new EmailHandler(mailProvider);
        imNotifyHandler = new ImNotifyHandler(environment);
        counterHandler = new CounterHandler(new com.veloflow.engine.runtime.WorkflowExpressionEvaluator());
        lenient().when(context.instance()).thenReturn(instance);
        lenient().when(instance.getId()).thenReturn("inst-1");
    }

    // ==================== database SQL 守卫 ====================

    @Test
    @DisplayName("SQL 守卫：SELECT 通过 / INSERT-UPDATE-DELETE-DROP 等写关键字拒绝 / 分号注释拒绝")
    void sqlGuard() {
        assertNull(DatabaseHandler.guard("SELECT * FROM t"));
        assertNull(DatabaseHandler.guard("with x as (select 1) select * from x"));
        assertEquals("仅允许 SELECT/WITH 查询", DatabaseHandler.guard("UPDATE t set a=1"));
        assertEquals("仅允许 SELECT/WITH 查询", DatabaseHandler.guard("DELETE FROM t"));
        assertEquals("禁止分号（多语句）", DatabaseHandler.guard("SELECT 1; DROP TABLE t"));
        assertEquals("禁止 SQL 注释", DatabaseHandler.guard("SELECT 1 -- comment"));
        assertEquals("禁止 SQL 注释", DatabaseHandler.guard("SELECT /* c */ 1"));
        assertEquals("禁止分号（多语句）", DatabaseHandler.guard("with w as (select 1) select * from w; delete from t"));
        assertTrue(DatabaseHandler.guard("with w as (insert into t values(1)) select * from w").contains("INSERT"));
        assertNull(DatabaseHandler.guard("select * from orders where note = 'delete me'"),
                "字符串字面量中的关键字不应误伤");
    }

    @Test
    @DisplayName("database 节点：合法 SELECT 经桥执行并输出行集")
    void databaseExecutesQuery() {
        when(context.config()).thenReturn(Map.of("dataSourceId", "wenshi", "sql", "SELECT 1"));
        when(context.variables()).thenReturn(Map.of());
        when(dataSourceBridge.query(eq("wenshi"), eq("SELECT 1"), eq(100)))
                .thenReturn(new FlowDataSourceBridge.QueryResult(List.of(Map.of("?column?", 1)), 1, 5));

        databaseHandler.activate(context);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(context).complete(captor.capture());
        assertTrue(captor.getValue().contains("\"rowCount\":1"));
    }

    // ==================== email / im-notify 未接入 ====================

    @Test
    @DisplayName("email 桥未接入：可读失败（含配置指引）")
    void emailBridgeMissing() {
        @SuppressWarnings("unchecked")
        ObjectProvider<FlowMailBridge> empty = org.mockito.Mockito.mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        EmailHandler handler = new EmailHandler(empty);
        when(context.config()).thenReturn(Map.of("to", "a@b.c", "subject", "s"));

        handler.activate(context);

        ArgumentCaptor<Boolean> success = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
        verify(context).complete(success.capture(), output.capture());
        assertEquals(false, success.getValue());
        assertTrue(output.getValue().contains("veloflow.mail.enabled"));
    }

    @Test
    @DisplayName("im-notify：渠道未配置 webhook 可读失败")
    void imNotifyChannelNotConfigured() {
        when(environment.getProperty("veloflow.im.webhooks.dingtalk")).thenReturn(null);
        when(context.config()).thenReturn(Map.of("channel", "dingtalk", "messageTemplate", "hi"));

        imNotifyHandler.activate(context);

        ArgumentCaptor<Boolean> success = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> output = ArgumentCaptor.forClass(String.class);
        verify(context).complete(success.capture(), output.capture());
        assertEquals(false, success.getValue());
        assertTrue(output.getValue().contains("未配置 webhook"));
    }

    @Test
    @DisplayName("im-notify：已配置渠道按平台格式推送（飞书 msg_type）")
    void imNotifySendsFeishuFormat() throws Exception {
        when(environment.getProperty("veloflow.im.webhooks.feishu")).thenReturn("https://open.feishu.cn/hook/x");
        when(context.config()).thenReturn(Map.of("channel", "feishu", "messageTemplate", "hi ${n}"));
        when(context.variables()).thenReturn(Map.of("n", 1));
        // HTTP 真发会失败——仅校验 payload 构造
        assertTrue(ImNotifyHandler.textPayload("feishu", "hi 1").contains("msg_type"));
        assertTrue(ImNotifyHandler.textPayload("dingtalk", "hi 1").contains("msgtype"));
    }

    // ==================== counter ====================

    @Test
    @DisplayName("counter：缺省从 0 累加 1，输出动态键平铺变量空间")
    void counterIncrements() {
        when(context.config()).thenReturn(Map.of("key", "retryCount"));
        when(context.variables()).thenReturn(Map.of());

        counterHandler.activate(context);

        verify(context).complete("{\"retryCount\":1}");
    }

    @Test
    @DisplayName("counter：读取现有变量累计 + 步长表达式 + 归零")
    void counterAccumulatesAndResets() {
        when(context.config()).thenReturn(Map.of("key", "cnt", "step", "2"));
        when(context.variables()).thenReturn(Map.of("cnt", 5));
        counterHandler.activate(context);
        verify(context).complete("{\"cnt\":7}");

        when(context.config()).thenReturn(Map.of("key", "cnt", "step", "1", "reset", "true"));
        when(context.variables()).thenReturn(Map.of("cnt", 5));
        org.mockito.Mockito.clearInvocations(context);
        counterHandler.activate(context);
        verify(context).complete("{\"cnt\":1}");
    }
}
