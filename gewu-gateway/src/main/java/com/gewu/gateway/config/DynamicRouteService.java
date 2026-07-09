package com.gewu.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Slf4j
@Configuration
public class DynamicRouteService {

    private final RouteDefinitionLocator routeDefinitionLocator;
    private final RouteDefinitionWriter routeDefinitionWriter;

    public DynamicRouteService(RouteDefinitionLocator routeDefinitionLocator,
                               RouteDefinitionWriter routeDefinitionWriter) {
        this.routeDefinitionLocator = routeDefinitionLocator;
        this.routeDefinitionWriter = routeDefinitionWriter;
    }

    public Mono<Void> addRoute(RouteDefinition definition) {
        log.info("动态添加路由: {}", definition.getId());
        return routeDefinitionWriter.save(Mono.just(definition));
    }

    public Mono<Void> removeRoute(String routeId) {
        log.info("动态移除路由: {}", routeId);
        return routeDefinitionWriter.delete(Mono.just(routeId));
    }
}
