package com.gewu.application.agent.adapter;

import com.gewu.agent.engine.hitl.UserInteractionGateway;
import com.gewu.common.result.BusinessException;
import com.gewu.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * chat 链路用户交互网关（ask_user 内置工具的挂起-恢复后端）。
 * <p>内存注册表：askId → Sinks.One 应答通道；引擎侧 ask_user 工具挂起在
 * askQuestion 的 Mono 上，用户经 REST 回答后 tryEmitValue 恢复执行；
 * 超时以"未在限时内回答"降级应答，任务不因用户离场而永久卡死。
 * <p>单实例内存态：进程重启丢挂起问（对应 run 本也会丢，与已知限制一致）。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.engine.adapter.enabled", havingValue = "true", matchIfMissing = false)
public class ChatUserInteractionGateway implements UserInteractionGateway {

    /** 跳过应答（前端"忽略"按钮）：模型据此自行决策继续 */
    public static final String SKIP_ANSWER = "（用户选择跳过此问题，请基于已有信息自主决策并继续任务）";

    private final Map<String, Sinks.One<String>> pendingAsks = new ConcurrentHashMap<>();
    private final Map<String, PendingAsk> pendingByAskId = new ConcurrentHashMap<>();
    private final Map<String, String> latestAskBySession = new ConcurrentHashMap<>();

    @Value("${gewu.chat.ask-timeout-seconds:600}")
    private int askTimeoutSeconds;

    @Override
    public Mono<String> askQuestion(AskRequest request) {
        Sinks.One<String> sink = Sinks.one();
        pendingAsks.put(request.getAskId(), sink);
        PendingAsk meta = new PendingAsk(request.getAskId(), request.getSessionId(), request.getQuestion(),
                request.getOptions() == null ? List.of() : request.getOptions(), System.currentTimeMillis());
        pendingByAskId.put(request.getAskId(), meta);
        if (request.getSessionId() != null) {
            latestAskBySession.put(request.getSessionId(), request.getAskId());
        }
        log.info("ask_user 挂起等待用户回答: askId={}, session={}, options={}",
                request.getAskId(), request.getSessionId(), meta.options().size());
        int timeout = request.getTimeoutSeconds() > 0 ? request.getTimeoutSeconds() : askTimeoutSeconds;
        return sink.asMono()
                .timeout(Duration.ofSeconds(timeout))
                .onErrorResume(e -> Mono.just("（用户未在限时内回答，视为跳过；请基于现有信息继续任务）"))
                .doFinally(sig -> {
                    pendingAsks.remove(request.getAskId());
                    pendingByAskId.remove(request.getAskId());
                    if (request.getSessionId() != null) {
                        latestAskBySession.remove(request.getSessionId(), request.getAskId());
                    }
                });
    }

    @Override
    public void answer(String askId, String answer) {
        Sinks.One<String> sink = pendingAsks.get(askId);
        if (sink == null) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "该提问不存在或已结束: " + askId);
        }
        sink.tryEmitValue(answer != null && !answer.isBlank() ? answer : SKIP_ANSWER);
    }

    /** 会话维度回答（校验 askId 确属该会话的挂起问，防跨会话恢复） */
    public void answerAsk(String sessionId, String askId, String answer) {
        PendingAsk meta = pendingByAskId.get(askId);
        if (meta == null || !meta.sessionId().equals(sessionId)) {
            throw BusinessException.of(ResultCode.PARAM_INVALID, "该提问不存在或不属于当前会话: " + askId);
        }
        answer(askId, answer);
    }

    /** 查询会话当前挂起问（断连重进后恢复问答框渲染；无挂起返回 null） */
    public PendingAsk pendingBySession(String sessionId) {
        String askId = latestAskBySession.get(sessionId);
        return askId == null ? null : pendingByAskId.get(askId);
    }

    /** 挂起问元数据 */
    public record PendingAsk(String askId, String sessionId, String question, List<String> options, long createdAt) {
    }
}
