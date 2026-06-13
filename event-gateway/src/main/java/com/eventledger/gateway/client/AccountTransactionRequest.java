package com.eventledger.gateway.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import com.eventledger.gateway.event.EventType;

public record AccountTransactionRequest(
        String eventId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata) {
}
