package com.gewu.interfaceapi.terminal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网页终端一次性 ticket（设计文档 44 §F5）：
 * interface 签发（用户 JWT 登录态 + dev 沙箱已就绪为前提），
 * 同进程消费——避免长 JWT 暴露在 WS 握手 URL。
 * 单次使用、TTL 60s；状态仅存内存（进程重启即失效，重新申领即可）。
 */
@Service
public class TerminalTicketService {

    private static final long TTL_MS = 60_000L;

    /** ticket → 签发载荷（包内可见，供代理端点读取） */
    record Payload(String userId, String sandboxId, long issuedAt, java.util.concurrent.atomic.AtomicBoolean used) {
    }

    private final Map<String, Payload> store = new ConcurrentHashMap<>();

    @Value("${gewu.terminal.ticket-ttl-ms:60000}")
    long ttlMs;

    public String issue(String userId, String sandboxId) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        store.put(ticket, new Payload(userId, sandboxId, System.currentTimeMillis(),
                new java.util.concurrent.atomic.AtomicBoolean(false)));
        // 顺手清理过期项，防无界增长
        store.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().issuedAt() > ttlMs);
        return ticket;
    }

    /** 单次消费：成功返回载荷；过期/已用/不存在抛 IllegalArgumentException */
    public Payload consume(String ticket) {
        Payload p = ticket == null ? null : store.remove(ticket);
        if (p == null || System.currentTimeMillis() - p.issuedAt() > ttlMs || !p.used().compareAndSet(false, true)) {
            throw new IllegalArgumentException("终端凭据无效或已过期");
        }
        return p;
    }
}
