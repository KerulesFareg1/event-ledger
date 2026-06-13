package com.eventledger.account.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import com.eventledger.account.transaction.TransactionType;

public record TransactionResponse(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata) {
}
