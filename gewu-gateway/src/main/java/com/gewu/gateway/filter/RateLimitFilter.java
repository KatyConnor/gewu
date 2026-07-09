package com.gewu.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final RedissonClient redissonClient;
    private final long permits;
    private final long timeoutSeconds;

    public RateLimitFilter(
            RedissonClient redissonClient,
            @Value("${gewu.gateway.rate-limit.permits:100}") long permits,
            @Value("${gewu.gateway.rate-limit.timeout-seconds:60}") long timeoutSeconds) {
        this.redissonClient = redissonClient;
        this.permits = permits;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String userId = request.getHeaders().getFirst("X-User-Id");
        String key = "rate_limit:" + (userId != null ? userId : request.getRemoteAddress().getAddress().getHostAddress());

        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        limiter.trySetRate(RateType.OVERALL, permits, timeoutSeconds, RateIntervalUnit.SECONDS);

        return Mono.fromCallable(limiter::tryAcquire)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(acquired -> {
                    ServerHttpResponse response = exchange.getResponse();
                    if (acquired) {
                        long remaining = limiter.availablePermits();
                        response.getHeaders().add("X-RateLimit-Remaining", String.valueOf(remaining));
                        return chain.filter(exchange);
                    }
                    response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    String body = "{\"code\":10007,\"message\":\"请求过于频繁\",\"data\":null,\"timestamp\":" + System.currentTimeMillis() + "}";
                    DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
                    return response.writeWith(Mono.just(buffer));
                });
    }

    @Override
    public int getOrder() {
        return -50;
    }
}
