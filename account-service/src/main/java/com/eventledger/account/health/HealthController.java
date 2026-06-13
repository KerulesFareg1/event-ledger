package com.eventledger.account.health;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.time.Duration;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try (Connection connection = dataSource.getConnection()) {
            boolean databaseHealthy = connection.isValid(1);
            HttpStatus status = databaseHealthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
            return ResponseEntity.status(status).body(Map.of(
                    "status", databaseHealthy ? "UP" : "DOWN",
                    "service", "account-service",
                    "database", Map.of(
                            "status", databaseHealthy ? "UP" : "DOWN",
                            "product", connection.getMetaData().getDatabaseProductName(),
                            "version", connection.getMetaData().getDatabaseProductVersion()),
                    "uptimeSeconds", Duration.ofMillis(
                            ManagementFactory.getRuntimeMXBean().getUptime()).toSeconds()));
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DOWN",
                    "service", "account-service",
                    "database", Map.of("status", "DOWN"),
                    "uptimeSeconds", Duration.ofMillis(
                            ManagementFactory.getRuntimeMXBean().getUptime()).toSeconds()));
        }
    }
}
