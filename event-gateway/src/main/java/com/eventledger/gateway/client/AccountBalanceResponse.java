package com.eventledger.gateway.client;

import java.math.BigDecimal;

public record AccountBalanceResponse(
        String accountId,
        BigDecimal balance,
        String currency) {
}
