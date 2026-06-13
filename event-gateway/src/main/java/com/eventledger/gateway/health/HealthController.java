package com.eventledger.gateway.health;

import java.sql.Connection;
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
    public ResponseEntity<Map<String, String>> health() {
        try (Connection connection = dataSource.getConnection()) {
            boolean databaseHealthy = connection.isValid(1);
            HttpStatus status = databaseHealthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
            return ResponseEntity.status(status).body(Map.of(
                    "status", databaseHealthy ? "UP" : "DOWN",
                    "service", "event-gateway",
                    "database", databaseHealthy ? "UP" : "DOWN"));
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DOWN",
                    "service", "event-gateway",
                    "database", "DOWN"));
        }
    }
}
