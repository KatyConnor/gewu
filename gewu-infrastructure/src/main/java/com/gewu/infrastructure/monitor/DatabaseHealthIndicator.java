package com.gewu.infrastructure.monitor;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
@RequiredArgsConstructor
public class DatabaseHealthIndicator extends AbstractHealthIndicator {

    private final DataSource dataSource;

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("SELECT 1");
            builder.up().withDetail("database", "MySQL");
        } catch (Exception e) {
            builder.down().withDetail("error", e.getMessage());
        }
    }
}