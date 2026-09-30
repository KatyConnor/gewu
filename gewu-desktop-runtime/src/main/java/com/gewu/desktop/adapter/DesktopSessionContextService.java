package com.gewu.desktop.adapter;

import com.gewu.agent.engine.llm.model.Message;
import com.gewu.agent.engine.spi.SessionContextService;
import com.gewu.desktop.config.DesktopProps;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌面会话上下文服务（D1-10）：引擎 SessionContextService SPI 的 H2 实现。
 * <p>历史构建：最近 N 条（默认 50，{@link DesktopProps#getHistoryLimit()}）按 seq 升序回放。
 * 上下文压缩（ContextCompactor SPI）在引擎超阈值时介入，桌面 v1 采用引擎 NoOp（截断保底）。
 * <p>交互持久化由 ChatController 拥有（流开始即落用户消息，崩溃不丢）；
 * 引擎若回调 {@link #appendInteraction}，因消息已落库此处幂等跳过。
 *
 * @since 1.0.0
 */
@Component
public class DesktopSessionContextService implements SessionContextService {

    private final JdbcTemplate jdbc;
    private final DesktopProps props;

    public DesktopSessionContextService(JdbcTemplate jdbc, DesktopProps props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public List<Message> buildContextMessages(String sessionId, int limit) {
        int effective = limit > 0 ? limit : props.getHistoryLimit();
        List<Message> recent = jdbc.query(
                "SELECT role, content FROM local_message WHERE session_id = ? AND role IN ('user','assistant') "
                        + "ORDER BY seq DESC LIMIT ?",
                (rs, i) -> {
                    Message m = new Message();
                    m.setRole(rs.getString(1));
                    m.setContent(rs.getString(2));
                    return m;
                }, sessionId, effective);
        List<Message> ordered = new ArrayList<>(recent);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    @Override
    public void appendInteraction(String sessionId, String userId, String userMessage, String assistantContent) {
        // 持久化归属 ChatController（流开始落用户消息、done 落助手消息），此处幂等跳过防双写
    }
}
