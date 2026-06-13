package com.eventledger.gateway.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import com.eventledger.gateway.event.EventType;

public record EventResponse(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata) {
}
